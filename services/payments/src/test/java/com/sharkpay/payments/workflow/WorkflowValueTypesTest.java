package com.sharkpay.payments.workflow;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Workflow value types: the command/outcome guards (Temporal serializes
 * these — blank ids must never reach a history) and the pinned task queue.
 */
class WorkflowValueTypesTest {

    @Test
    void commandsRejectBlankPaymentIds() {
        assertThat(new PaymentCommand("pay_1").paymentId()).isEqualTo("pay_1");
        assertThatThrownBy(() -> new PaymentCommand(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paymentId");
        assertThatThrownBy(() -> new PaymentCommand(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paymentId");
    }

    @Test
    void outcomesRejectBlankIdsAndStates() {
        PaymentOutcome outcome = new PaymentOutcome("pay_1", "SUCCEEDED", "rail_confirmed");
        assertThat(outcome.paymentId()).isEqualTo("pay_1");
        assertThat(outcome.state()).isEqualTo("SUCCEEDED");
        assertThat(outcome.reason()).isEqualTo("rail_confirmed");
        // a null reason is legal (successes carry none)
        assertThat(new PaymentOutcome("pay_1", "FAILED", null).reason()).isNull();

        assertThatThrownBy(() -> new PaymentOutcome(null, "SUCCEEDED", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("paymentId");
        assertThatThrownBy(() -> new PaymentOutcome("pay_1", " ", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("state");
    }

    @Test
    void theTaskQueueIsThePaymentsNamespace() {
        // ADR 002: per-runtime namespaces, one task queue per workflow
        // implementation — pinned so a rename cannot drift silently
        assertThat(PaymentWorkflow.TASK_QUEUE).isEqualTo("payments");
    }
}
