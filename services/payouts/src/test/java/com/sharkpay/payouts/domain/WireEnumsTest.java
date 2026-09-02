package com.sharkpay.payouts.domain;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Wire enums of both aggregates: wire names, case-insensitive parsing and
 * the terminal/cancellable predicates that back the API's 409 semantics.
 */
class WireEnumsTest {

    @Test
    void payoutStatesParseCaseInsensitively() {
        assertThat(PayoutState.fromWire("PENDING_RISK")).isEqualTo(PayoutState.PENDING_RISK);
        assertThat(PayoutState.fromWire(" pending_risk ")).isEqualTo(PayoutState.PENDING_RISK);
        assertThatThrownBy(() -> PayoutState.fromWire(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payout state is required");
        assertThatThrownBy(() -> PayoutState.fromWire("PENDING"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void payoutTerminalStatesAreExactlyTheContractSet() {
        for (PayoutState state : new PayoutState[]{PayoutState.SUCCEEDED, PayoutState.FAILED,
                PayoutState.RETURNED, PayoutState.BLOCKED, PayoutState.CANCELLED}) {
            assertThat(state.isTerminal()).as("%s", state).isTrue();
        }
        for (PayoutState state : new PayoutState[]{PayoutState.CREATED, PayoutState.PENDING_RISK,
                PayoutState.PROCESSING, PayoutState.SENT}) {
            assertThat(state.isTerminal()).as("%s", state).isFalse();
        }
    }

    @Test
    void payoutCancellableStatesAreCreatedAndPendingRiskOnly() {
        assertThat(PayoutState.CREATED.isCancellable()).isTrue();
        assertThat(PayoutState.PENDING_RISK.isCancellable()).isTrue();
        for (PayoutState state : new PayoutState[]{PayoutState.PROCESSING, PayoutState.SENT,
                PayoutState.SUCCEEDED, PayoutState.FAILED, PayoutState.RETURNED,
                PayoutState.BLOCKED, PayoutState.CANCELLED}) {
            assertThat(state.isCancellable()).as("%s", state).isFalse();
        }
    }

    @Test
    void payoutWireNamesAreTheContractEnumValues() {
        assertThat(PayoutState.values()).hasSize(9);
        for (PayoutState state : PayoutState.values()) {
            assertThat(state.wireName()).isEqualTo(state.name());
        }
    }

    @Test
    void transferStatesParseCaseInsensitively() {
        assertThat(TransferState.fromWire("SUCCEEDED")).isEqualTo(TransferState.SUCCEEDED);
        assertThat(TransferState.fromWire(" failed ")).isEqualTo(TransferState.FAILED);
        assertThatThrownBy(() -> TransferState.fromWire(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transfer state is required");
        assertThatThrownBy(() -> TransferState.fromWire("PENDING"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transferTerminalStatesAreSucceededAndFailed() {
        assertThat(TransferState.SUCCEEDED.isTerminal()).isTrue();
        assertThat(TransferState.FAILED.isTerminal()).isTrue();
        assertThat(TransferState.CREATED.isTerminal()).isFalse();
        for (TransferState state : TransferState.values()) {
            assertThat(state.wireName()).isEqualTo(state.name());
        }
    }

    @Test
    void walletIdsFollowThePublicPattern() {
        assertThat(Wallet.ID_PATTERN.matcher("wal_0123456789abcdef0123456789abcdef").matches())
                .isTrue();
        assertThat(Wallet.ID_PATTERN.matcher("wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A").matches()).isTrue();
        for (String bad : new String[]{"wal_short", "wallet-1", "wal_0123456789abcdef012345!",
                "WAL_0123456789abcdef0123456789abcdef"}) {
            assertThat(Wallet.ID_PATTERN.matcher(bad).matches()).isFalse();
        }
    }

    @Test
    void payoutAndTransferIdsFollowTheirPublicPatterns() {
        assertThat(Payout.ID_PATTERN.matcher("pot_0123456789abcdef0123456789abcdef").matches())
                .isTrue();
        assertThat(Transfer.ID_PATTERN.matcher("trf_0123456789abcdef0123456789abcdef").matches())
                .isTrue();
        assertThat(Payout.ID_PATTERN.matcher("trf_0123456789abcdef0123456789abcdef").matches())
                .isFalse();
        assertThat(Transfer.ID_PATTERN.matcher("pot_0123456789abcdef0123456789abcdef").matches())
                .isFalse();
        assertThat(Transfer.ID_PATTERN.matcher("trf_short").matches()).isFalse();
    }
}
