package com.sharkpay.payments.config;

import com.sharkpay.money.Money;
import com.sharkpay.payments.domain.Destination;
import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.domain.Rail;
import com.sharkpay.payments.ports.LedgerPort.EntryType;
import com.sharkpay.payments.ports.ProviderGatewayPort;
import com.sharkpay.payments.ports.ProviderGatewayPort.TransferStatus;
import com.sharkpay.payments.testsupport.PaymentsTestEnv;
import com.sharkpay.payments.workflow.PaymentActivitiesImpl;
import com.sharkpay.payments.workflow.PaymentWorkflow;
import com.sharkpay.payments.workflow.PaymentWorkflowImpl;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import io.temporal.worker.Worker;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The production {@link PaymentLifecyclePort} adapter over Temporal on the
 * in-memory {@link TestWorkflowEnvironment} (no server, ADR 003 §2.4):
 * {@link TemporalPaymentLifecycle#start} starts PaymentWorkflow for the
 * payment (workflow id {@code payment-<id>}, task queue "payments"), the
 * in-process in-flight set makes a repeated start a no-op, and a start
 * re-fired after a "process restart" (fresh adapter, same client) against a
 * still-running workflow swallows Temporal's
 * {@code WorkflowExecutionAlreadyStarted} — the hand-off's existence is the
 * goal, so a retry after a crash can never fail a request.
 */
class TemporalPaymentLifecycleTest {

    private PaymentsTestEnv env;
    private TestWorkflowEnvironment temporal;
    private BlockingGateway gateway;

    @BeforeEach
    void setUp() {
        env = new PaymentsTestEnv();
        // the activities see a gateway that can park its first poll on a
        // latch, so a started workflow is provably still RUNNING while the
        // test re-fires the start (deterministic WorkflowExecutionAlreadyStarted)
        gateway = new BlockingGateway(env.gateway);
        temporal = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setInitialTime(PaymentsTestEnv.START)
                .build());
        Worker worker = temporal.newWorker(PaymentWorkflow.TASK_QUEUE);
        worker.registerWorkflowImplementationTypes(PaymentWorkflowImpl.class);
        worker.registerActivitiesImplementations(new PaymentActivitiesImpl(env.evaluateRisk,
                env.placeHold, env.handoff, env.recordResult, env.failPayment, env.expirePayment,
                env.getPayment, gateway, PaymentsTestEnv.POLL_INTERVAL_MS));
        temporal.start();
    }

    @AfterEach
    void tearDown() {
        temporal.close();
    }

    @Test
    void startHandsThePaymentToTheWorkflowWhichDrivesItToSucceeded() {
        String paymentId = persistedCreatedIntent("key-1");
        env.gateway.pollScript(TransferStatus.SUCCEEDED);

        TemporalPaymentLifecycle lifecycle = new TemporalPaymentLifecycle(
                temporal.getWorkflowClient());
        lifecycle.start(paymentId);

        PaymentIntent done = awaitTerminal(paymentId);
        assertThat(done.state()).isEqualTo(PaymentState.SUCCEEDED);
        // the saga ran through the workflow alone: one hold, one capture,
        // no release (money-state alignment, STATE-MACHINES.md §7.4)
        assertThat(env.ledger.effectCount(done.internalId(), EntryType.HOLD)).isEqualTo(1);
        assertThat(env.ledger.effectCount(done.internalId(), EntryType.CAPTURE)).isEqualTo(1);
        assertThat(env.ledger.effectCount(done.internalId(), EntryType.RELEASE)).isZero();
        assertThat(env.events.eventsOfType("payments.payment.succeeded.v1")).hasSize(1);

        // same process: a second start is an in-memory no-op (no exception,
        // no second workflow execution, no new effect)
        int effects = env.ledger.totalEffects();
        lifecycle.start(paymentId);
        assertThat(env.ledger.totalEffects()).isEqualTo(effects);
        assertThat(env.gateway.initiatedByKey()).hasSize(1);
    }

    @Test
    void aRestartedAdapterSwallowsAlreadyStartedForARunningWorkflow() {
        String paymentId = persistedCreatedIntent("key-1");
        // the next poll parks on the gate: the workflow stays RUNNING
        env.gateway.pollScript(TransferStatus.SUCCEEDED);
        gateway.parkNextPoll();

        TemporalPaymentLifecycle first = new TemporalPaymentLifecycle(
                temporal.getWorkflowClient());
        first.start(paymentId);
        awaitParkedPoll(); // the workflow is now inside its blocked poll activity

        // "process restart": a fresh adapter (empty in-process in-flight set)
        // with the same client — the workflow payment-<id> is still RUNNING,
        // so Temporal rejects the duplicate start; the adapter treats that
        // as the success it is
        TemporalPaymentLifecycle restarted = new TemporalPaymentLifecycle(
                temporal.getWorkflowClient());
        assertThatCode(() -> restarted.start(paymentId))
                .as("a duplicate start of a running workflow must not fail the request")
                .doesNotThrowAnyException();

        // release the parked poll: the single workflow run completes
        gateway.releaseGate();
        PaymentIntent done = awaitTerminal(paymentId);
        assertThat(done.state()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(env.ledger.effectCount(done.internalId(), EntryType.CAPTURE)).isEqualTo(1);
    }

    @Test
    void startRejectsNullPaymentIdsAndTheConstructorRequiresAClient() {
        assertThatThrownBy(() -> new TemporalPaymentLifecycle(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("workflowClient");
        TemporalPaymentLifecycle lifecycle = new TemporalPaymentLifecycle(
                temporal.getWorkflowClient());
        assertThatThrownBy(() -> lifecycle.start(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("paymentId");
    }

    // ── helpers ───────────────────────────────────────────────────────────

    /** Persists a fresh CREATED intent the workflow drives end to end. */
    private String persistedCreatedIntent(String key) {
        String paymentId = env.randomness.paymentId();
        PaymentIntent intent = PaymentIntent.newIntent(paymentId, env.randomness.uuidV7(),
                env.principals.principalId(), null,
                Destination.internalWallet(PaymentsTestEnv.WALLET), Money.of(150_000L, "KES"),
                Money.of(750L, "KES"), key, Rail.HONEYCOIN,
                PaymentsTestEnv.START.plus(Duration.ofSeconds(900)), Map.of(),
                PaymentsTestEnv.START);
        env.payments.save(intent);
        return paymentId;
    }

    private static final java.util.Set<PaymentState> TERMINAL = java.util.Set.of(
            PaymentState.SUCCEEDED, PaymentState.FAILED, PaymentState.EXPIRED,
            PaymentState.REVERSED, PaymentState.BLOCKED, PaymentState.CANCELLED);

    /** Waits (wall clock) for the workflow to land the intent in a terminal state. */
    private PaymentIntent awaitTerminal(String paymentId) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            PaymentIntent intent = env.payments.findById(paymentId).orElseThrow();
            if (TERMINAL.contains(intent.state())) {
                return intent;
            }
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while awaiting workflow completion", e);
            }
        }
        throw new AssertionError("workflow did not reach a terminal state: " + paymentId);
    }

    private void awaitParkedPoll() {
        long deadline = System.currentTimeMillis() + 30_000;
        while (!gateway.parked() && System.currentTimeMillis() < deadline) {
            try {
                Thread.sleep(10);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new AssertionError("interrupted while awaiting the parked poll", e);
            }
        }
        assertThat(gateway.parked()).as("the next poll must park on the gate").isTrue();
    }

    /** Gateway wrapper whose next poll (when armed) blocks until released. */
    private static final class BlockingGateway implements ProviderGatewayPort {
        private final ProviderGatewayPort delegate;
        private volatile CountDownLatch gate;
        private volatile boolean parked;

        BlockingGateway(ProviderGatewayPort delegate) {
            this.delegate = Objects.requireNonNull(delegate);
        }

        /** Arms the one-shot gate: the NEXT poll parks until released. */
        void parkNextPoll() {
            gate = new CountDownLatch(1);
        }

        void releaseGate() {
            CountDownLatch current = gate;
            if (current != null) {
                current.countDown();
            }
        }

        boolean parked() {
            return parked;
        }

        @Override
        public TransferStatus poll(ProviderRef ref) {
            CountDownLatch current = gate;
            if (current != null) {
                parked = true;
                gate = null; // one-shot
                try {
                    current.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException("parked poll interrupted", e);
                }
            }
            return delegate.poll(ref);
        }

        @Override
        public java.util.List<ProviderCandidateView> candidates() {
            return delegate.candidates();
        }

        @Override
        public Quote quote(QuoteRequest request) {
            return delegate.quote(request);
        }

        @Override
        public ProviderRef initiate(InitiateRequest request) {
            return delegate.initiate(request);
        }

        @Override
        public void cancel(ProviderRef ref) {
            delegate.cancel(ref);
        }
    }
}
