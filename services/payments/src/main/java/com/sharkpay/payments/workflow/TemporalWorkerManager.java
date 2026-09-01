package com.sharkpay.payments.workflow;

import io.temporal.client.WorkflowClient;
import io.temporal.worker.Worker;
import io.temporal.worker.WorkerFactory;
import org.springframework.context.SmartLifecycle;

import java.util.Objects;

/**
 * Temporal worker lifecycle: builds a {@link WorkerFactory} and one
 * {@link Worker} on {@link PaymentWorkflow#TASK_QUEUE} registering
 * {@link PaymentWorkflowImpl} + the activities, and starts it.
 *
 * <p>Guarded by {@code temporal.enabled=true} in
 * {@code TemporalWorkerConfig} — with the flag off (the default) the bean is
 * never created and unit tests never need a Temporal server (ADR 003 §2.4).
 * Plain class (no Spring annotations) so it is directly unit-testable
 * against a {@code TestWorkflowEnvironment} client.</p>
 */
public final class TemporalWorkerManager implements SmartLifecycle {

    private final WorkflowClient client;
    private final PaymentActivitiesImpl activities;
    private final String taskQueue;
    private volatile WorkerFactory factory;
    private volatile boolean running;

    public TemporalWorkerManager(WorkflowClient client, PaymentActivitiesImpl activities,
                                 String taskQueue) {
        this.client = Objects.requireNonNull(client, "workflowClient is required");
        this.activities = Objects.requireNonNull(activities, "activities are required");
        this.taskQueue = Objects.requireNonNull(taskQueue, "taskQueue is required");
    }

    @Override
    public void start() {
        WorkerFactory factory = WorkerFactory.newInstance(client);
        Worker worker = factory.newWorker(taskQueue);
        worker.registerWorkflowImplementationTypes(PaymentWorkflowImpl.class);
        worker.registerActivitiesImplementations(activities);
        factory.start();
        this.factory = factory;
        this.running = true;
    }

    @Override
    public void stop() {
        WorkerFactory current = factory;
        if (current != null) {
            current.shutdown();
        }
        running = false;
    }

    @Override
    public boolean isRunning() {
        return running;
    }

    /** The task queue this manager serves (diagnostics). */
    public String taskQueue() {
        return taskQueue;
    }
}
