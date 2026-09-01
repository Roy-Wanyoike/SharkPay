package com.sharkpay.payments.config;

import com.sharkpay.payments.testsupport.PaymentsTestEnv;
import com.sharkpay.payments.workflow.PaymentWorkflow;
import com.sharkpay.payments.workflow.TemporalWorkerManager;
import io.temporal.client.WorkflowClient;
import io.temporal.serviceclient.WorkflowServiceStubs;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The {@code temporal.enabled=true} worker wiring (TemporalWorkerConfig):
 * every bean factory builds a real, usable object WITHOUT a Temporal server —
 * the gRPC service stubs connect lazily on the first RPC, so construction is
 * offline-safe — and the {@link ConditionalOnProperty} guard is pinned so the
 * default profile can never accidentally boot a worker against nothing
 * (application.yml: {@code temporal.enabled: ${TEMPORAL_ENABLED:false}}).
 */
class TemporalWorkerConfigTest {

    private final PaymentsTestEnv env = new PaymentsTestEnv();
    private final TemporalWorkerConfig config = new TemporalWorkerConfig();

    @Test
    void beanFactoriesBuildRealObjectsOffline() {
        WorkflowServiceStubs stubs = config.workflowServiceStubs("localhost:7233");
        assertThat(stubs).isNotNull();
        try {
            WorkflowClient client = config.workflowClient(stubs, "sharkpay-payments");
            assertThat(client.getOptions().getNamespace()).isEqualTo("sharkpay-payments");

            assertThat(config.temporalPaymentLifecycle(client))
                    .isInstanceOf(TemporalPaymentLifecycle.class);

            TemporalWorkerManager manager = config.temporalWorkerManager(client, env.activities);
            assertThat(manager.taskQueue()).isEqualTo(PaymentWorkflow.TASK_QUEUE);
            assertThat(manager.isRunning()).isFalse(); // starts with the app lifecycle
        } finally {
            // the @Bean(destroyMethod = "shutdown") contract: stubs retire cleanly
            stubs.shutdownNow();
        }
    }

    @Test
    void theWorkerWiringIsGuardedByTemporalEnabledTrue() {
        ConditionalOnProperty guard = TemporalWorkerConfig.class
                .getAnnotation(ConditionalOnProperty.class);
        assertThat(guard).as("the worker beans must stay behind the temporal.enabled guard")
                .isNotNull();
        assertThat(guard.prefix()).isEqualTo("temporal");
        assertThat(guard.name()).containsExactly("enabled");
        assertThat(guard.havingValue()).isEqualTo("true");
    }

    @Test
    void applicationYmlDefaultsTheWorkerOffAndDocumentsTheFailFastPlaceholders() {
        String yml = readApplicationYml();
        // the guarded wiring boots nothing by default: the lifecycle port
        // stays the logging placeholder (PaymentsConfigTest)
        assertThat(yml).contains("enabled: ${TEMPORAL_ENABLED:false}");
        assertThat(yml).contains("address: ${TEMPORAL_ADDRESS:localhost:7233}");
        assertThat(yml).contains("namespace: ${TEMPORAL_NAMESPACE:sharkpay-payments}");
        // router region input (README "Router policy", ops-owned)
        assertThat(yml).contains("default-region: ${PAYMENTS_ROUTER_REGION:KE}");
        // optional fields are omitted, not null (payments.yaml
        // additionalProperties: false; events likewise)
        assertThat(yml).contains("default-property-inclusion: non_null");
    }

    private static String readApplicationYml() {
        for (Path candidate : java.util.List.of(
                Path.of("src/main/resources/application.yml"),
                Path.of("services/payments/src/main/resources/application.yml"))) {
            try {
                if (Files.isRegularFile(candidate)) {
                    return Files.readString(candidate);
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + candidate, e);
            }
        }
        throw new IllegalStateException("application.yml not found (working dir "
                + Path.of("").toAbsolutePath() + ")");
    }
}
