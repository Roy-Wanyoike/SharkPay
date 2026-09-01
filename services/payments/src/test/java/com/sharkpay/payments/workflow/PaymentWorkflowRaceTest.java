package com.sharkpay.payments.workflow;

import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.ports.LedgerPort.EntryType;
import com.sharkpay.payments.ports.ProviderGatewayPort.TransferStatus;
import com.sharkpay.payments.testsupport.PaymentsTestEnv;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Money-safety under TERMINAL-STATE RACES (ADR 003 G2): the REST prefix, the
 * expiry timer, user cancels and provider callbacks can all land while the
 * workflow is mid-flight. These tests pin the workflow's answers to the two
 * races the loop must survive:
 *
 * <ul>
 *   <li>the user cancels while the workflow is polling — the poll activity
 *       reports the intent's own (now terminal) state, the loop stops and
 *       the winner (CANCELLED) is reported; no second release, no capture;</li>
 *   <li>the rail confirms (SUCCEEDED) at the same moment the intent is
 *       cancelled — the capture activity hits the state guard, its retries
 *       exhaust, and the workflow reports the winning terminal state instead
 *       of double-terminal-ing or capturing a cancelled payment.</li>
 * </ul>
 *
 * <p>The races are injected through a delegating activity wrapper (the only
 * deterministic way to interleave a REST-side mutation with the workflow's
 * own activity calls) — the wrapper performs the mutation exactly once, then
 * behaves exactly like the real activities.</p>
 */
class PaymentWorkflowRaceTest {

    private PaymentsTestEnv env;
    private TestWorkflowEnvironment temporal;

    @AfterEach
    void tearDown() {
        if (temporal != null) {
            temporal.close();
        }
    }

    @Test
    void anExternalCancelWhilePollingParksThePollAndReportsTheWinner() {
        // the prefix ran (hold placed + initiated); the workflow will now
        // poll — and the FIRST poll triggers the user's concurrent cancel
        // exactly as a REST request landing between two activity calls would
        env = new PaymentsTestEnv();
        PaymentIntent intent = env.createDefault();
        CancelOnceOnFirstPoll wrapper = new CancelOnceOnFirstPoll(intent.id());
        start(wrapper);

        PaymentOutcome outcome = orchestrate(intent.id());

        // the winner is the user's CANCELLED — never EXPIRED, never FAILED
        assertThat(outcome.paymentId()).isEqualTo(intent.id());
        assertThat(outcome.state()).isEqualTo("CANCELLED");
        assertThat(env.payments.findById(intent.id()).orElseThrow().state())
                .isEqualTo(PaymentState.CANCELLED);

        // money-safety: the cancel released the hold exactly once; the
        // workflow added NO second release, NO capture, NO expiry effect
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.HOLD)).isEqualTo(1);
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.RELEASE)).isEqualTo(1);
        assertThat(env.ledger.attemptCount(intent.internalId(), EntryType.RELEASE)).isEqualTo(1);
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.CAPTURE)).isZero();
        assertThat(env.walletHolds.wasReleased(intent.internalId())).isTrue();
        assertThat(env.walletHolds.capturedHolds()).isEmpty();

        // the poll that raced saw a terminal intent (nothing in flight on
        // the wire) and reported the intent's own state — the loop
        // terminated on THAT, not on the expiry timer or a failure guess
        assertThat(wrapper.polls).isGreaterThanOrEqualTo(1);
        assertThat(env.gateway.polls()).isEmpty(); // the wire was never read
        assertThat(env.events.eventsOfType("payments.payment.expired.v1")).isEmpty();
        assertThat(env.events.eventsOfType("payments.payment.failed.v1")).isEmpty();
    }

    @Test
    void aCaptureRacedByATerminalStateReportsTheWinnerNeverDoubleTerminals() {
        // the rail confirms (SUCCEEDED) at the same moment the intent is
        // cancelled: the capture activity runs into the state guard, its
        // retry policy exhausts, and the workflow must surface the winning
        // terminal state — with zero capture, ever
        env = new PaymentsTestEnv();
        PaymentIntent intent = env.createDefault();
        env.gateway.pollScript(TransferStatus.SUCCEEDED);
        CancelOnceOnFirstCapture wrapper = new CancelOnceOnFirstCapture(intent.id());
        start(wrapper);

        PaymentOutcome outcome = orchestrate(intent.id());

        assertThat(outcome.state()).isEqualTo("CANCELLED");
        assertThat(env.payments.findById(intent.id()).orElseThrow().state())
                .isEqualTo(PaymentState.CANCELLED);

        // no capture of a cancelled payment — not on any of the retries
        assertThat(wrapper.captures).isGreaterThanOrEqualTo(1);
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.CAPTURE)).isZero();
        assertThat(env.walletHolds.capturedHolds()).isEmpty();
        // the hold was released exactly once — by the racing cancel, never
        // by a second compensation from the exhausted capture path
        assertThat(env.ledger.effectCount(intent.internalId(), EntryType.RELEASE)).isEqualTo(1);
        assertThat(env.ledger.attemptCount(intent.internalId(), EntryType.RELEASE)).isEqualTo(1);
        // and no succeeded event was ever published for the cancelled intent
        assertThat(env.events.eventsOfType("payments.payment.succeeded.v1")).isEmpty();
    }

    // ── helpers ───────────────────────────────────────────────────────────

    private void start(PaymentActivities activities) {
        temporal = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setInitialTime(PaymentsTestEnv.START)
                .build());
        Worker worker = temporal.newWorker(PaymentWorkflow.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(PaymentWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        // starts the WorkerFactory pollers (newWorker does not auto-start)
        temporal.start();
    }

    private PaymentOutcome orchestrate(String paymentId) {
        PaymentWorkflow workflow = temporal.getWorkflowClient().newWorkflowStub(
                PaymentWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId("race-" + paymentId)
                        .setTaskQueue(PaymentWorkflow.TASK_QUEUE)
                        .build());
        return workflow.orchestrate(new PaymentCommand(paymentId));
    }

    /** Delegates everything; cancels the payment once on the first poll. */
    private final class CancelOnceOnFirstPoll extends RacingActivities {
        private int polls;

        private CancelOnceOnFirstPoll(String paymentId) {
            super(paymentId);
        }

        @Override
        public PollResult pollProvider(String paymentId) {
            cancelOnce();
            polls++;
            return env.activities.pollProvider(paymentId);
        }

        @Override
        public PaymentOutcome captureAndSettle(String paymentId) {
            return env.activities.captureAndSettle(paymentId);
        }
    }

    /** Delegates everything; cancels the payment once on the first capture. */
    private final class CancelOnceOnFirstCapture extends RacingActivities {
        private int captures;

        private CancelOnceOnFirstCapture(String paymentId) {
            super(paymentId);
        }

        @Override
        public PollResult pollProvider(String paymentId) {
            return env.activities.pollProvider(paymentId);
        }

        @Override
        public PaymentOutcome captureAndSettle(String paymentId) {
            cancelOnce();
            captures++;
            return env.activities.captureAndSettle(paymentId);
        }
    }

    /**
     * Base wrapper: the shared one-shot cancel (idempotent anyway — the use
     * case's state guard makes a second call a no-op) plus pure delegation
     * for every other activity.
     */
    private abstract class RacingActivities implements PaymentActivities {
        private final String paymentId;
        private boolean cancelled;

        private RacingActivities(String paymentId) {
            this.paymentId = paymentId;
        }

        protected void cancelOnce() {
            if (!cancelled) {
                cancelled = true;
                env.cancelPayment.cancel("race-cancel-" + paymentId, paymentId);
            }
        }

        @Override
        public PaymentSnapshot loadSnapshot(String paymentId) {
            return env.activities.loadSnapshot(paymentId);
        }

        @Override
        public RiskResult evaluateRisk(String paymentId) {
            return env.activities.evaluateRisk(paymentId);
        }

        @Override
        public HoldResult placeHold(String paymentId) {
            return env.activities.placeHold(paymentId);
        }

        @Override
        public String routeAndInitiate(String paymentId, int tierRank) {
            return env.activities.routeAndInitiate(paymentId, tierRank);
        }

        @Override
        public void confirmProcessing(String paymentId) {
            env.activities.confirmProcessing(paymentId);
        }

        @Override
        public PaymentOutcome failPayment(String paymentId, String reason) {
            return env.activities.failPayment(paymentId, reason);
        }

        @Override
        public PaymentOutcome expirePayment(String paymentId) {
            return env.activities.expirePayment(paymentId);
        }

        @Override
        public PaymentOutcome outcome(String paymentId) {
            return env.activities.outcome(paymentId);
        }
    }
}
