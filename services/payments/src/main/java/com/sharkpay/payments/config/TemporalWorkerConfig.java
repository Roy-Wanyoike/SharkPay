package com.sharkpay.payments.config;

import com.sharkpay.payments.workflow.PaymentActivitiesImpl;
import com.sharkpay.payments.workflow.PaymentWorkflow;
import com.sharkpay.payments.workflow.TemporalWorkerManager;
import io.temporal.client.WorkflowClient;
import io.temporal.client.WorkflowClientOptions;
import io.temporal.serviceclient.WorkflowServiceStubs;
import io.temporal.serviceclient.WorkflowServiceStubsOptions;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;

/**
 * Temporal worker wiring, guarded by {@code temporal.enabled=true} (default
 * false): with the flag off NONE of these beans exist — the application
 * boots without a Temporal server (dev, and local unit tests never need
 * one), the lifecycle port stays the logging placeholder, and provider
 * results arrive through the internal lifecycle API. With the flag on:
 *
 * <ul>
 *   <li>gRPC service stubs to {@code temporal.address} (lazy — the first
 *       workflow call connects);</li>
 *   <li>a {@link WorkflowClient} on namespace {@code temporal.namespace};</li>
 *   <li>{@link TemporalPaymentLifecycle} as the {@code @Primary} lifecycle
 *       port (starts PaymentWorkflow per payment id on task queue
 *       {@value PaymentWorkflow#TASK_QUEUE});</li>
 *   <li>a {@link TemporalWorkerManager} (SmartLifecycle) that registers
 *       PaymentWorkflowImpl + the activities and serves the queue.</li>
 * </ul>
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "temporal", name = "enabled", havingValue = "true")
public class TemporalWorkerConfig {

    @Bean(destroyMethod = "shutdown")
    public WorkflowServiceStubs workflowServiceStubs(
            @Value("${temporal.address:localhost:7233}") String address) {
        return WorkflowServiceStubs.newInstance(WorkflowServiceStubsOptions.newBuilder()
                .setTarget(address)
                .build());
    }

    @Bean
    public WorkflowClient workflowClient(WorkflowServiceStubs stubs,
                                         @Value("${temporal.namespace:sharkpay-payments}")
                                         String namespace) {
        return WorkflowClient.newInstance(stubs, WorkflowClientOptions.newBuilder()
                .setNamespace(namespace)
                .build());
    }

    @Bean
    @Primary
    public TemporalPaymentLifecycle temporalPaymentLifecycle(WorkflowClient client) {
        return new TemporalPaymentLifecycle(client);
    }

    @Bean
    public TemporalWorkerManager temporalWorkerManager(WorkflowClient client,
                                                       PaymentActivitiesImpl activities) {
        return new TemporalWorkerManager(client, activities, PaymentWorkflow.TASK_QUEUE);
    }
}
