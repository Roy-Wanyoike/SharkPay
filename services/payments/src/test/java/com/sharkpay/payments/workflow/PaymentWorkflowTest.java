package com.sharkpay.payments.workflow;

import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.ports.LedgerPort.EntryType;
import com.sharkpay.payments.ports.ProviderGatewayPort.TransferStatus;
import com.sharkpay.payments.ports.RiskPort.Phase;
import com.sharkpay.payments.testsupport.PaymentsTestEnv;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * PaymentWorkflow saga tests on Temporal's in-memory
 * {@link TestWorkflowEnvironment} (temporal-testing — no server, ADR 003
 * §2.4): risk → hold → route → initiate → poll → confirm → capture with
 * compensation on every failure path, the expiry timer, activity retry
 * policies and idempotent replay of the synchronous REST creation prefix.
 *
 * <p>The environment's virtual clock is pinned to
 * {@link PaymentsTestEnv#START} so the workflow's expiry deadline arithmetic
 * and the domain's {@code MutableClock} agree, and time-skipping makes every
 * poll interval and retry backoff instant.</p>
 */
class PaymentWorkflowTest {

    private PaymentsTestEnv env;
    private TestWorkflowEnvironment temporal;

    @BeforeEach
    void setUp() {
        env = new PaymentsTestEnv();
        temporal = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setInitialTime(PaymentsTestEnv.START)
                .build());
        Worker worker = temporal.newWorker(PaymentWorkflow.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(PaymentWorkflowImpl.class);
        worker.registerActivitiesImplementations(env.activities);
        // starts the WorkerFactory pollers: without this the workflow task is
        // never picked up and the client blocks forever on the history
        // long-poll (TestWorkflowEnvironment.newWorker does not auto-start)
        temporal.start();
    }

    @AfterEach
    void tearDown() {
        temporal.close();
    }

    // ── the full saga, driven by the workflow alone (no REST prefix) ───────

    @Test
    void drivesACreatedIntentRiskHoldRouteInitiateConfirmCaptureToSucceeded() {
        String paymentId = persistedCreatedIntent("key-1", 900);
        env.gateway.pollScript(TransferStatus.SUCCEEDED);

        PaymentOutcome outcome = orchestrate(paymentId);

        assertThat(outcome.paymentId()).isEqualTo(paymentId);
        assertThat(outcome.state()).isEqualTo("SUCCEEDED");
        assertThat(outcome.reason()).isNull();

        PaymentIntent intent = env.payments.findById(paymentId).orElseThrow();
        assertThat(intent.state()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(intent.provider()).isEqualTo("honeycoin");
        assertThat(intent.providerRef()).isNotBlank();

        // money state alignment (STATE-MACHINES.md §7.4): exactly one hold
        // entry + one capture entry, no release — captured, not compensated
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.HOLD)).isEqualTo(1);
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.CAPTURE)).isEqualTo(1);
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.RELEASE)).isZero();
        assertThat(env.walletHolds.hasHold(intent.internalId())).isTrue();
        assertThat(env.walletHolds.capturedHolds()).hasSize(1);
        assertThat(env.walletHolds.wasReleased(intent.internalId())).isFalse();

        // risk ran pre- AND post-authorization (the SUCCEEDED guard, §1)
        assertThat(env.risk.evaluationOf(intent.id()).phase()).isEqualTo(Phase.PRE_AUTHORIZATION);
        assertThat(env.risk.evaluations()).hasSize(2);
        assertThat(env.risk.evaluations().get(1).phase()).isEqualTo(Phase.POST_AUTHORIZATION);

        // one wire initiate, keyed by the intent's internal id
        assertThat(env.gateway.initiateAttempts()).isEqualTo(1);
        assertThat(env.gateway.initiatedByKey()).containsKey(intent.internalId().toString());

        // the workflow published the two catalog events of its steps
        assertThat(env.events.eventsOfType("payments.payment.pending_provider.v1")).hasSize(1);
        assertThat(env.events.eventsOfType("payments.payment.succeeded.v1")).hasSize(1);

        // full timeline: CREATED → PENDING_PROVIDER → PROCESSING → SUCCEEDED
        assertThat(env.payments.transitionsOf(paymentId))
                .extracting(com.sharkpay.payments.domain.StateTransition::to)
                .containsExactly(PaymentState.CREATED, PaymentState.PENDING_PROVIDER,
                        PaymentState.PROCESSING, PaymentState.SUCCEEDED);
    }

    @Test
    void pollingProgressesThroughProcessingBeforeTheRailConfirms() {
        String paymentId = persistedCreatedIntent("key-1", 900);
        env.gateway.pollScript(TransferStatus.PENDING, TransferStatus.PROCESSING,
                TransferStatus.SUCCEEDED);

        PaymentOutcome outcome = orchestrate(paymentId);

        assertThat(outcome.state()).isEqualTo("SUCCEEDED");
        // three poll cycles ran (PENDING parked, PROCESSING confirmed,
        // SUCCEEDED captured) — the poll cadence is workflow time, skipped
        assertThat(env.gateway.polls().size()).isGreaterThanOrEqualTo(3);
        assertThat(env.payments.transitionsOf(paymentId))
                .extracting(com.sharkpay.payments.domain.StateTransition::to)
                .contains(PaymentState.PROCESSING);
        assertThat(env.payments.findById(paymentId).orElseThrow().state())
                .isEqualTo(PaymentState.SUCCEEDED);
    }

    // ── risk gates ────────────────────────────────────────────────────────

    @Test
    void riskDenyBlocksTheIntentBeforeAnyMoneyMoves() {
        env.risk.next(com.sharkpay.payments.fakes.FakeRiskPort.deny("velocity spike"));
        String paymentId = persistedCreatedIntent("key-1", 900);

        PaymentOutcome outcome = orchestrate(paymentId);

        assertThat(outcome.state()).isEqualTo("BLOCKED");
        assertThat(env.payments.findById(paymentId).orElseThrow().state())
                .isEqualTo(PaymentState.BLOCKED);
        // payments.yaml: failure_reason is present only when state is FAILED
        // — the BLOCKED reason lives in the append-only transition log
        assertThat(env.payments.findById(paymentId).orElseThrow().failureReason()).isNull();
        assertThat(env.payments.transitionsOf(paymentId))
                .anySatisfy(transition -> assertThat(transition.reason()).contains("risk_deny"));
        // "risk deny: no money moved" (§1 side-effect column)
        assertThat(env.ledger.totalEffects()).isZero();
        assertThat(env.walletHolds.placedHolds()).isEmpty();
        assertThat(env.gateway.initiations()).isEmpty();
        assertThat(env.lifecycle.startsOf(paymentId)).isZero();
    }

    @Test
    void riskReviewFailsClosedToBlocked() {
        env.risk.next(com.sharkpay.payments.fakes.FakeRiskPort.review("manual review"));
        String paymentId = persistedCreatedIntent("key-1", 900);

        assertThat(orchestrate(paymentId).state()).isEqualTo("BLOCKED");
        assertThat(env.ledger.totalEffects()).isZero();
        assertThat(env.gateway.initiations()).isEmpty();
    }

    // ── compensation on every failure path ────────────────────────────────

    @Test
    void definitiveProviderRejectionCompensatesImmediatelyWithoutRetries() {
        env.gateway.rejectNextInitiations(1);
        String paymentId = persistedCreatedIntent("key-1", 900);

        PaymentOutcome outcome = orchestrate(paymentId);

        assertThat(outcome.state()).isEqualTo("FAILED");
        assertThat(outcome.reason()).contains("rail rejected");
        PaymentIntent intent = env.payments.findById(paymentId).orElseThrow();
        // hold was placed by the workflow, then released exactly once
        assertThat(env.walletHolds.wasReleased(intent.internalId())).isTrue();
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.RELEASE)).isEqualTo(1);
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.CAPTURE)).isZero();
        // non-retryable business rejection: one wire attempt, no retry storm
        assertThat(env.gateway.initiateAttempts()).isEqualTo(1);
        assertThat(env.events.eventsOfType("payments.payment.failed.v1")).hasSize(1);
    }

    @Test
    void initiateRetriesThroughTransientUnavailabilityThenSucceeds() {
        env.gateway.unavailableNextInitiations(2);
        env.gateway.pollScript(TransferStatus.SUCCEEDED);
        String paymentId = persistedCreatedIntent("key-1", 900);

        PaymentOutcome outcome = orchestrate(paymentId);

        assertThat(outcome.state()).isEqualTo("SUCCEEDED");
        // two transient failures, third attempt succeeded (retry policy
        // allows 4); exactly one wire effect (idempotency on the key)
        assertThat(env.gateway.initiateAttempts()).isEqualTo(3);
        assertThat(env.gateway.initiatedByKey()).hasSize(1);
        assertThat(env.ledger.effectCount(env.payments.findById(paymentId).orElseThrow()
                .internalId(), EntryType.CAPTURE)).isEqualTo(1);
    }

    @Test
    void exhaustedInitiateRetriesCompensateAndFail() {
        env.gateway.unavailableNextInitiations(10);
        String paymentId = persistedCreatedIntent("key-1", 900);

        PaymentOutcome outcome = orchestrate(paymentId);

        assertThat(outcome.state()).isEqualTo("FAILED");
        assertThat(outcome.reason()).isEqualTo("provider_unavailable");
        PaymentIntent intent = env.payments.findById(paymentId).orElseThrow();
        // the retry policy ran out (INITIATE_MAX_ATTEMPTS = 4)
        assertThat(env.gateway.initiateAttempts()).isEqualTo(4);
        assertThat(env.gateway.initiatedByKey()).isEmpty();
        // compensation exactly once: hold released + RELEASE entry
        assertThat(env.walletHolds.wasReleased(intent.internalId())).isTrue();
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.RELEASE)).isEqualTo(1);
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.HOLD)).isEqualTo(1);
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.CAPTURE)).isZero();
    }

    @Test
    void noEligibleProviderFailsClosedWithCompensation() {
        env.gateway.clearCandidates();
        String paymentId = persistedCreatedIntent("key-1", 900);

        PaymentOutcome outcome = orchestrate(paymentId);

        assertThat(outcome.state()).isEqualTo("FAILED");
        PaymentIntent intent = env.payments.findById(paymentId).orElseThrow();
        assertThat(intent.failureReason()).contains("no_eligible_provider");
        assertThat(env.gateway.initiations()).isEmpty();
        assertThat(env.walletHolds.wasReleased(intent.internalId())).isTrue();
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.RELEASE)).isEqualTo(1);
    }

    @Test
    void providerFailureStatusFailsThePaymentWithCompensation() {
        PaymentIntent intent = env.createDefault(); // synchronous prefix ran
        env.gateway.pollScript(TransferStatus.FAILED);

        PaymentOutcome outcome = orchestrate(intent.id());

        assertThat(outcome.state()).isEqualTo("FAILED");
        assertThat(env.walletHolds.wasReleased(intent.internalId())).isTrue();
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.RELEASE)).isEqualTo(1);
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.CAPTURE)).isZero();
        assertThat(env.events.eventsOfType("payments.payment.failed.v1")).hasSize(1);
    }

    @Test
    void providerReturnedStatusCompensatesLikeAFailure() {
        // funds bounced back from the rail: same compensation path as FAILED
        PaymentIntent intent = env.createDefault();
        env.gateway.pollScript(TransferStatus.RETURNED);

        PaymentOutcome outcome = orchestrate(intent.id());

        assertThat(outcome.state()).isEqualTo("FAILED");
        assertThat(env.payments.findById(intent.id()).orElseThrow().failureReason())
                .isEqualTo("provider_returned");
        assertThat(env.walletHolds.wasReleased(intent.internalId())).isTrue();
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.RELEASE)).isEqualTo(1);
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.CAPTURE)).isZero();
    }

    @Test
    void anAlreadySucceededIntentSkipsRouteAndPollAndReturnsItsState() {
        // SUCCEEDED/FAILED are terminal-for-the-money-flow but keep the
        // REVERSED successor, so loadSnapshot's isTerminal() does not catch
        // them — routeAndInitiate must report SKIPPED and the outcome must
        // return the state unchanged with zero new effects
        PaymentIntent intent = env.createDefault();
        env.recordResult.record(null, intent.id(), "SUCCEEDED");
        int effects = env.ledger.totalEffects();
        int initiations = env.gateway.initiateAttempts();
        env.events.reset();

        PaymentOutcome outcome = orchestrate(intent.id());

        assertThat(outcome.state()).isEqualTo("SUCCEEDED");
        assertThat(outcome.reason()).isNull();
        assertThat(env.ledger.totalEffects()).isEqualTo(effects);
        assertThat(env.gateway.initiateAttempts()).isEqualTo(initiations);
        assertThat(env.gateway.polls()).isEmpty(); // nothing in flight to poll
        assertThat(env.events.events()).isEmpty();
    }

    @Test
    void postAuthorizationRiskDenyCompensatesInsteadOfCapturing() {
        PaymentIntent intent = env.createDefault(); // PENDING_PROVIDER, held
        env.risk.byDefault(com.sharkpay.payments.fakes.FakeRiskPort.deny("blocked payee"));
        env.gateway.pollScript(TransferStatus.SUCCEEDED); // rail confirms...

        PaymentOutcome outcome = orchestrate(intent.id());

        // ...but "SUCCEEDED is reachable only after risk post-evaluation
        // passed" (§1 guard): the capture is refused and the saga compensates
        assertThat(outcome.state()).isEqualTo("FAILED");
        assertThat(env.payments.findById(intent.id()).orElseThrow().failureReason())
                .contains("post_authorization_risk");
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.CAPTURE)).isZero();
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.RELEASE)).isEqualTo(1);
        assertThat(env.walletHolds.capturedHolds()).isEmpty();
        assertThat(env.walletHolds.wasReleased(intent.internalId())).isTrue();
    }

    // ── expiry timer ──────────────────────────────────────────────────────

    @Test
    void theExpiryTimerExpiresAnUnconfirmedIntentAndReleasesTheHoldOnce() {
        // shortest payments.yaml TTL (60s) so the timer is cheap to traverse
        PaymentIntent intent = env.createPayment.create("key-1",
                env.principals.principalId(), 150_000L, "KES", PaymentsTestEnv.WALLET,
                "honeycoin", Map.of(), 60).intent();
        assertThat(intent.expiresAt()).isEqualTo(PaymentsTestEnv.START.plusSeconds(60));

        // both clocks move past the TTL: the workflow timer fires AND the
        // domain guard (expiry only from PENDING_PROVIDER, §1) agrees
        env.clock.advance(Duration.ofSeconds(61));
        temporal.sleep(Duration.ofSeconds(61));

        PaymentOutcome outcome = orchestrate(intent.id());

        assertThat(outcome.state()).isEqualTo("EXPIRED");
        // expiry released the hold exactly once — never a second release on
        // re-delivery, never a capture
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.RELEASE)).isEqualTo(1);
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.CAPTURE)).isZero();
        assertThat(env.walletHolds.wasReleased(intent.internalId())).isTrue();
        assertThat(env.events.eventsOfType("payments.payment.expired.v1")).hasSize(1);
        // poll happened before the timer check, but never resolved anything
        assertThat(env.gateway.polls()).isNotEmpty();
        assertThat(env.gateway.pollScriptExhausted()).isTrue();
    }

    // ── idempotent replay (the REST prefix already advanced the intent) ───

    @Test
    void replayingTheWorkflowAfterTheSynchronousPrefixNeverDoublesEffects() {
        PaymentIntent intent = env.createDefault(); // prefix: hold + initiate
        env.gateway.pollScript(TransferStatus.SUCCEEDED);
        int ledgerEffectsAfterPrefix = env.ledger.totalEffects();
        int initiationsAfterPrefix = env.gateway.initiatedByKey().size();

        PaymentOutcome outcome = orchestrate(intent.id());

        assertThat(outcome.state()).isEqualTo("SUCCEEDED");
        // one initiate on the wire (the workflow's routeAndInitiate replayed
        // the provider ref), one hold, one capture — no double anything
        assertThat(env.gateway.initiatedByKey()).hasSize(initiationsAfterPrefix);
        assertThat(env.ledger.totalEffects()).isEqualTo(ledgerEffectsAfterPrefix + 1);
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.HOLD)).isEqualTo(1);
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.CAPTURE)).isEqualTo(1);
        assertThat(env.walletHolds.capturedHolds()).hasSize(1);
        // pre-auth risk ran once (prefix); post-auth once (capture)
        assertThat(env.risk.evaluations()).hasSize(2);
    }

    @Test
    void aTerminalIntentIsReturnedAsIsWithNoNewEffects() {
        PaymentIntent intent = env.createDefault();
        env.cancelPayment.cancel("cancel-key", intent.id()); // → CANCELLED
        int effects = env.ledger.totalEffects();
        env.events.reset();

        PaymentOutcome outcome = orchestrate(intent.id());

        assertThat(outcome.state()).isEqualTo("CANCELLED");
        assertThat(env.ledger.totalEffects()).isEqualTo(effects);
        assertThat(env.events.events()).isEmpty();
        assertThat(env.gateway.polls()).isEmpty();
    }

    @Test
    void pollUnavailabilityNeverFailsAPaymentOffAReadError() {
        PaymentIntent intent = env.createDefault();
        // one full poll cycle (3 attempts) exhausts its retries...
        env.gateway.unavailableNextPolls(3);
        // ...the next cycle's first poll succeeds with the rail's answer
        env.gateway.pollScript(TransferStatus.SUCCEEDED);

        PaymentOutcome outcome = orchestrate(intent.id());

        assertThat(outcome.state()).isEqualTo("SUCCEEDED");
        assertThat(env.gateway.polls()).isNotEmpty();
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.CAPTURE)).isEqualTo(1);
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.RELEASE)).isZero();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Runs the workflow synchronously and returns its outcome. */
    private PaymentOutcome orchestrate(String paymentId) {
        PaymentWorkflow workflow = temporal.getWorkflowClient().newWorkflowStub(
                PaymentWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId("test-" + paymentId)
                        .setTaskQueue(PaymentWorkflow.TASK_QUEUE)
                        .build());
        return workflow.orchestrate(new PaymentCommand(paymentId));
    }

    /**
     * Persists a fresh intent in CREATED (the workflow runs every step
     * itself: risk, hold, route+initiate, poll, capture) — the state the
     * Temporal worker sees when the synchronous REST prefix did not run.
     */
    private String persistedCreatedIntent(String key, int ttlSeconds) {
        String paymentId = env.randomness.paymentId();
        UUID internalId = env.randomness.uuidV7();
        PaymentIntent intent = PaymentIntent.newIntent(paymentId, internalId,
                env.principals.principalId(), null,
                com.sharkpay.payments.domain.Destination.internalWallet(PaymentsTestEnv.WALLET),
                com.sharkpay.money.Money.of(150_000L, "KES"),
                com.sharkpay.money.Money.of(750L, "KES"), key,
                com.sharkpay.payments.domain.Rail.HONEYCOIN,
                PaymentsTestEnv.START.plus(Duration.ofSeconds(ttlSeconds)), Map.of(),
                PaymentsTestEnv.START);
        env.payments.save(intent);
        return paymentId;
    }
}
