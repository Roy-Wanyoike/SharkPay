package com.sharkpay.payments.workflow;

import com.sharkpay.money.Money;
import com.sharkpay.payments.domain.Destination;
import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.domain.Rail;
import com.sharkpay.payments.ports.LedgerPort.EntryType;
import com.sharkpay.payments.ports.ProviderGatewayPort.TransferStatus;
import com.sharkpay.payments.testsupport.PaymentsTestEnv;
import io.temporal.client.WorkflowOptions;
import io.temporal.testing.TestEnvironmentOptions;
import io.temporal.testing.TestWorkflowEnvironment;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * {@link TemporalWorkerManager}: the SmartLifecycle bean that registers
 * PaymentWorkflowImpl + the activities on task queue "payments" and serves
 * it — the worker the {@code temporal.enabled=true} wiring runs. Verified
 * against the in-memory {@link TestWorkflowEnvironment}: a workflow started
 * through the plain client completes because the manager-started worker
 * executes it (no server, ADR 003 §2.4).
 */
class TemporalWorkerManagerTest {

    private PaymentsTestEnv env;
    private TestWorkflowEnvironment temporal;
    private TemporalWorkerManager manager;

    @BeforeEach
    void setUp() {
        env = new PaymentsTestEnv();
        temporal = TestWorkflowEnvironment.newInstance(TestEnvironmentOptions.newBuilder()
                .setInitialTime(PaymentsTestEnv.START)
                .build());
        // the queue is served ONLY by the manager under test
        manager = new TemporalWorkerManager(temporal.getWorkflowClient(), env.activities,
                PaymentWorkflow.TASK_QUEUE);
    }

    @AfterEach
    void tearDown() {
        manager.stop();
        temporal.close();
    }

    @Test
    void startServesTheTaskQueueAndStopRetiresIt() {
        assertThat(manager.isRunning()).isFalse();
        assertThat(manager.taskQueue()).isEqualTo(PaymentWorkflow.TASK_QUEUE);
        assertThat(PaymentWorkflow.TASK_QUEUE).isEqualTo("payments");

        manager.start();
        assertThat(manager.isRunning()).isTrue();

        // a workflow started through the plain client completes: the
        // manager-registered worker is serving the queue
        env.gateway.pollScript(TransferStatus.SUCCEEDED);
        String paymentId = persistedCreatedIntent("key-1");
        PaymentOutcome outcome = orchestrate(paymentId);
        assertThat(outcome.state()).isEqualTo("SUCCEEDED");
        PaymentIntent done = env.payments.findById(paymentId).orElseThrow();
        assertThat(done.state()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(env.ledger.effectCount(done.internalId(), EntryType.CAPTURE)).isEqualTo(1);

        manager.stop();
        assertThat(manager.isRunning()).isFalse();
    }

    @Test
    void theConstructorRejectsNullArguments() {
        assertThatThrownBy(() -> new TemporalWorkerManager(null, env.activities, "q"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("workflowClient");
        assertThatThrownBy(() -> new TemporalWorkerManager(temporal.getWorkflowClient(), null,
                "q"))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("activities");
        assertThatThrownBy(() -> new TemporalWorkerManager(temporal.getWorkflowClient(),
                env.activities, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("taskQueue");
    }

    private PaymentOutcome orchestrate(String paymentId) {
        PaymentWorkflow workflow = temporal.getWorkflowClient().newWorkflowStub(
                PaymentWorkflow.class,
                WorkflowOptions.newBuilder()
                        .setWorkflowId("manager-test-" + paymentId)
                        .setTaskQueue(PaymentWorkflow.TASK_QUEUE)
                        .build());
        return workflow.orchestrate(new PaymentCommand(paymentId));
    }

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
}
