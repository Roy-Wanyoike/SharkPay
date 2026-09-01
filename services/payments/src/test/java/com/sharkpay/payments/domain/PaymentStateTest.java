package com.sharkpay.payments.domain;

import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The payment state machine (docs/STATE-MACHINES.md §1 — "any transition not
 * listed there is a bug"): the full legal matrix, every illegal edge
 * rejected, terminal/cancellable/saga-live classification and wire parsing.
 */
class PaymentStateTest {

    @Test
    void allowsExactlyTheDocumentedLegalEdges() {
        // CREATED
        assertThat(PaymentState.CREATED.canTransitionTo(PaymentState.PENDING_PROVIDER)).isTrue();
        assertThat(PaymentState.CREATED.canTransitionTo(PaymentState.BLOCKED)).isTrue();
        assertThat(PaymentState.CREATED.canTransitionTo(PaymentState.CANCELLED)).isTrue();
        // PENDING_PROVIDER
        assertThat(PaymentState.PENDING_PROVIDER.canTransitionTo(PaymentState.PROCESSING)).isTrue();
        assertThat(PaymentState.PENDING_PROVIDER.canTransitionTo(PaymentState.FAILED)).isTrue();
        assertThat(PaymentState.PENDING_PROVIDER.canTransitionTo(PaymentState.EXPIRED)).isTrue();
        assertThat(PaymentState.PENDING_PROVIDER.canTransitionTo(PaymentState.CANCELLED)).isTrue();
        // PROCESSING
        assertThat(PaymentState.PROCESSING.canTransitionTo(PaymentState.SUCCEEDED)).isTrue();
        assertThat(PaymentState.PROCESSING.canTransitionTo(PaymentState.FAILED)).isTrue();
        // explicit reversal edges
        assertThat(PaymentState.SUCCEEDED.canTransitionTo(PaymentState.REVERSED)).isTrue();
        assertThat(PaymentState.FAILED.canTransitionTo(PaymentState.REVERSED)).isTrue();
    }

    @Test
    void rejectsEveryEdgeTheStateMachineDoesNotList() {
        assertThat(PaymentState.CREATED.canTransitionTo(PaymentState.PROCESSING)).isFalse();
        assertThat(PaymentState.CREATED.canTransitionTo(PaymentState.SUCCEEDED)).isFalse();
        assertThat(PaymentState.CREATED.canTransitionTo(PaymentState.FAILED)).isFalse();
        assertThat(PaymentState.CREATED.canTransitionTo(PaymentState.EXPIRED)).isFalse();
        assertThat(PaymentState.CREATED.canTransitionTo(PaymentState.REVERSED)).isFalse();
        // expiry only from PENDING_PROVIDER (§1 guard)
        assertThat(PaymentState.PROCESSING.canTransitionTo(PaymentState.EXPIRED)).isFalse();
        // capture only from PROCESSING
        assertThat(PaymentState.PENDING_PROVIDER.canTransitionTo(PaymentState.SUCCEEDED))
                .isFalse();
        // never leave a terminal state
        for (PaymentState terminal : Set.of(PaymentState.EXPIRED, PaymentState.REVERSED,
                PaymentState.BLOCKED, PaymentState.CANCELLED)) {
            for (PaymentState any : PaymentState.values()) {
                assertThat(terminal.canTransitionTo(any))
                        .as("terminal %s must have no outgoing edge (attempted %s)", terminal, any)
                        .isFalse();
            }
        }
        // SUCCEEDED/FAILED only move via the explicit reversal edge
        assertThat(PaymentState.SUCCEEDED.canTransitionTo(PaymentState.FAILED)).isFalse();
        assertThat(PaymentState.SUCCEEDED.canTransitionTo(PaymentState.PROCESSING)).isFalse();
        assertThat(PaymentState.FAILED.canTransitionTo(PaymentState.SUCCEEDED)).isFalse();
    }

    @Test
    void terminalStatesAreExactlyThoseWithoutSuccessors() {
        assertThat(PaymentState.CREATED.isTerminal()).isFalse();
        assertThat(PaymentState.PENDING_PROVIDER.isTerminal()).isFalse();
        assertThat(PaymentState.PROCESSING.isTerminal()).isFalse();
        assertThat(PaymentState.SUCCEEDED.isTerminal()).isFalse();
        assertThat(PaymentState.FAILED.isTerminal()).isFalse();
        assertThat(PaymentState.EXPIRED.isTerminal()).isTrue();
        assertThat(PaymentState.REVERSED.isTerminal()).isTrue();
        assertThat(PaymentState.BLOCKED.isTerminal()).isTrue();
        assertThat(PaymentState.CANCELLED.isTerminal()).isTrue();
    }

    @Test
    void cancellableExactlyFromCreatedAndPendingProvider() {
        // payments.yaml cancelPayment: CREATED or PENDING_PROVIDER only
        assertThat(PaymentState.CREATED.isCancellable()).isTrue();
        assertThat(PaymentState.PENDING_PROVIDER.isCancellable()).isTrue();
        assertThat(PaymentState.PROCESSING.isCancellable()).isFalse();
        assertThat(PaymentState.SUCCEEDED.isCancellable()).isFalse();
        assertThat(PaymentState.FAILED.isCancellable()).isFalse();
    }

    @Test
    void sagaLiveWhileMoneyControlMayStillBeNeeded() {
        assertThat(PaymentState.CREATED.isSagaLive()).isTrue();
        assertThat(PaymentState.PENDING_PROVIDER.isSagaLive()).isTrue();
        assertThat(PaymentState.PROCESSING.isSagaLive()).isTrue();
        assertThat(PaymentState.SUCCEEDED.isSagaLive()).isFalse();
        assertThat(PaymentState.FAILED.isSagaLive()).isFalse();
        assertThat(PaymentState.EXPIRED.isSagaLive()).isFalse();
    }

    @Test
    void legalSuccessorsAreDefensiveCopies() {
        Set<PaymentState> successors = PaymentState.CREATED.legalSuccessors();
        assertThat(successors).containsExactlyInAnyOrder(PaymentState.PENDING_PROVIDER,
                PaymentState.BLOCKED, PaymentState.CANCELLED);
        assertThatThrownBy(() -> successors.add(PaymentState.PROCESSING))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void parsesWireValuesCaseSensitively() {
        for (PaymentState state : PaymentState.values()) {
            assertThat(PaymentState.fromWire(state.wireName())).isEqualTo(state);
            assertThat(PaymentState.fromWire(" " + state.wireName() + " ")).isEqualTo(state);
        }
        assertThatThrownBy(() -> PaymentState.fromWire("succeeded"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown payment state");
        assertThatThrownBy(() -> PaymentState.fromWire(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payment state is required");
    }

}
