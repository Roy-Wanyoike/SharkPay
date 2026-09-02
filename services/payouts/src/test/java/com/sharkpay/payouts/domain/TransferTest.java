package com.sharkpay.payouts.domain;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Transfer aggregate per docs/STATE-MACHINES.md §3: CREATED → SUCCEEDED
 * (ledger confirmation) or CREATED → FAILED (rejection — never partially
 * posted). Every other transition is a bug (409). Construction validates
 * ids, wallets, amount and the fixed non-negative same-currency fee.
 */
class TransferTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final String ID = "trf_0123456789abcdef0123456789abcdef";
    private static final String SOURCE = "wal_0123456789abcdef0123456789abcdef";
    private static final String DEST = "wal_fedcba9876543210fedcba9876543210";

    @Test
    void instantiateCreatesACreatedTransferWithZeroFeeAndNoSideEffects() {
        Transfer transfer = Transfer.instantiate(ID, UUID.randomUUID(), SOURCE, DEST,
                Money.of(25_000, "KES"), Map.of("reason", "invoice"), T0);

        assertThat(transfer.id()).isEqualTo(ID);
        assertThat(transfer.state()).isEqualTo(TransferState.CREATED);
        assertThat(transfer.amount()).isEqualTo(Money.of(25_000, "KES"));
        // transfers.yaml: V1 internal transfers are zero-fee
        assertThat(transfer.fee()).isEqualTo(Money.zero("KES"));
        assertThat(transfer.entryId()).isNull();
        assertThat(transfer.failureReason()).isNull();
        assertThat(transfer.createdAt()).isEqualTo(T0);
        assertThat(transfer.updatedAt()).isEqualTo(T0);
        assertThat(transfer.metadata()).containsEntry("reason", "invoice");
        assertThat(transfer.transitions()).isEmpty();
        assertThat(transfer.isTerminal()).isFalse();
    }

    @Test
    void instantiateDefaultsNullMetadataToEmptyAndIsImmutable() {
        Transfer transfer = Transfer.instantiate(ID, UUID.randomUUID(), SOURCE, DEST,
                Money.of(1, "KES"), null, T0);
        assertThat(transfer.metadata()).isEmpty();
        assertThatThrownBy(() -> transfer.metadata().put("k", "v"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void markSucceededMovesCreatedToSucceededAndRecordsTheLedgerEntry() {
        Transfer transfer = transfer();
        UUID entryId = UUID.randomUUID();

        transfer.markSucceeded(entryId, T0.plusSeconds(1));

        assertThat(transfer.state()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(transfer.entryId()).isEqualTo(entryId);
        assertThat(transfer.updatedAt()).isEqualTo(T0.plusSeconds(1));
        assertThat(transfer.isTerminal()).isTrue();
        assertThat(transfer.transitions()).hasSize(1);
        StateTransition transition = transfer.transitions().get(0);
        assertThat(transition.from()).isEqualTo(TransferState.CREATED);
        assertThat(transition.to()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(transition.trigger()).isEqualTo("ledger_confirmation");
        assertThat(transition.actor()).isEqualTo("system");
        assertThat(transition.occurredAt()).isEqualTo(T0.plusSeconds(1));
    }

    @Test
    void markFailedTerminatesWithTheTrimmedReasonAndNeverSetsAnEntry() {
        Transfer transfer = transfer();

        transfer.markFailed("  insufficient_funds: wallet overdrawn  ", T0.plusSeconds(2));

        assertThat(transfer.state()).isEqualTo(TransferState.FAILED);
        assertThat(transfer.entryId()).isNull();
        assertThat(transfer.failureReason()).isEqualTo("insufficient_funds: wallet overdrawn");
        assertThat(transfer.isTerminal()).isTrue();
        assertThat(transfer.transitions()).hasSize(1);
        assertThat(transfer.transitions().get(0).from()).isEqualTo(TransferState.CREATED);
        assertThat(transfer.transitions().get(0).to()).isEqualTo(TransferState.FAILED);
    }

    @Test
    void aSucceededTransferIsTerminalForBothSettleAndFail() {
        Transfer succeeded = transfer();
        succeeded.markSucceeded(UUID.randomUUID(), T0);

        assertThatThrownBy(() -> succeeded.markSucceeded(UUID.randomUUID(), T0))
                .isInstanceOf(TransferStateException.class)
                .hasMessageContaining(ID)
                .hasMessageContaining("SUCCEEDED");
        assertThatThrownBy(() -> succeeded.markFailed("late failure", T0))
                .isInstanceOf(TransferStateException.class);
    }

    @Test
    void aFailedTransferIsTerminalForBothSettleAndFail() {
        Transfer failed = transfer();
        failed.markFailed("ledger rejected", T0);

        assertThatThrownBy(() -> failed.markSucceeded(UUID.randomUUID(), T0))
                .isInstanceOf(TransferStateException.class);
        assertThatThrownBy(() -> failed.markFailed("again", T0))
                .isInstanceOf(TransferStateException.class);
    }

    @Test
    void markSucceededRequiresTheLedgerEntryId() {
        assertThatThrownBy(() -> transfer().markSucceeded(null, T0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void markFailedRequiresANonBlankReason() {
        assertThatThrownBy(() -> transfer().markFailed(null, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("failure reason is required");
        assertThatThrownBy(() -> transfer().markFailed("   ", T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void markFailedRejectsReasonsOver512Characters() {
        assertThatThrownBy(() -> transfer().markFailed("x".repeat(513), T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 512");
        // 512 exactly is fine
        Transfer transfer = transfer();
        transfer.markFailed("x".repeat(512), T0);
        assertThat(transfer.failureReason()).hasSize(512);
    }

    @Test
    void pendingTransitionsDrainExactlyOnceForRepositoryPersistence() {
        Transfer transfer = transfer();
        transfer.markSucceeded(UUID.randomUUID(), T0);

        assertThat(transfer.pendingTransitions()).hasSize(1);
        transfer.markTransitionsPersisted();
        assertThat(transfer.pendingTransitions()).isEmpty();
        assertThat(transfer.transitions()).hasSize(1);
    }

    @Test
    void theRehydrationConstructorRestoresTheFullAuditTrail() {
        UUID entryId = UUID.randomUUID();
        StateTransition history = new StateTransition(TransferState.CREATED,
                TransferState.SUCCEEDED, "ledger_confirmation", "system", null, T0);
        Transfer rehydrated = new Transfer(ID, UUID.randomUUID(), SOURCE, DEST,
                Money.of(5_000, "KES"), Money.zero("KES"), TransferState.SUCCEEDED, entryId, null,
                Map.of(), T0, T0, List.of(history));

        assertThat(rehydrated.state()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(rehydrated.entryId()).isEqualTo(entryId);
        assertThat(rehydrated.transitions()).containsExactly(history);
        assertThat(rehydrated.pendingTransitions()).isEmpty();
    }

    @Test
    void theIdMustMatchThePublicPattern() {
        for (String bad : new String[]{null, "", "trf_short", "pay_0123456789abcdef012345",
                "trf_0123456789abcdef0123456789abcde!", "TRF_0123456789abcdef0123456789abcdef"}) {
            assertThatThrownBy(() -> Transfer.instantiate(bad, UUID.randomUUID(), SOURCE, DEST,
                    Money.of(1, "KES"), null, T0))
                    .as("id %s", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("transfer id must match");
        }
    }

    @Test
    void bothWalletsMustMatchTheWalletPatternAndDiffer() {
        assertThatThrownBy(() -> Transfer.instantiate(ID, UUID.randomUUID(), "wallet-1", DEST,
                Money.of(1, "KES"), null, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sourceWalletId must match");
        assertThatThrownBy(() -> Transfer.instantiate(ID, UUID.randomUUID(), SOURCE, "nope",
                Money.of(1, "KES"), null, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("destinationWalletId must match");
        // same wallet: domain 422 same_wallet, checked twice (id equality)
        assertThatThrownBy(() -> Transfer.instantiate(ID, UUID.randomUUID(), SOURCE, SOURCE,
                Money.of(1, "KES"), null, T0))
                .isInstanceOf(SameWalletException.class)
                .hasMessageContaining(SOURCE);
    }

    @Test
    void theAmountMustBePositive() {
        assertThatThrownBy(() -> Transfer.instantiate(ID, UUID.randomUUID(), SOURCE, DEST,
                Money.zero("KES"), null, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transfer amount must be positive");
        assertThatThrownBy(() -> Transfer.instantiate(ID, UUID.randomUUID(), SOURCE, DEST,
                Money.of(-1, "KES"), null, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transfer amount must be positive");
    }

    @Test
    void theFeeMustBeNonNegativeSameCurrencyAndNotNull() {
        UUID ref = UUID.randomUUID();
        assertThatThrownBy(() -> new Transfer(ID, ref, SOURCE, DEST, Money.of(1, "KES"),
                Money.of(-1, "KES"), TransferState.CREATED, null, null, null, T0, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("transfer fee must be non-negative");
        assertThatThrownBy(() -> new Transfer(ID, ref, SOURCE, DEST, Money.of(1, "KES"),
                Money.zero("USD"), TransferState.CREATED, null, null, null, T0, T0))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> new Transfer(ID, ref, SOURCE, DEST, Money.of(1, "KES"), null,
                TransferState.CREATED, null, null, null, T0, T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("fee is required");
    }

    @Test
    void aPositiveSameCurrencyFeeIsAcceptedByTheRehydrationConstructor() {
        Transfer transfer = new Transfer(ID, UUID.randomUUID(), SOURCE, DEST, Money.of(1_000,
                "KES"), Money.of(5, "KES"), TransferState.SUCCEEDED, UUID.randomUUID(), null,
                null, T0, T0);
        assertThat(transfer.fee()).isEqualTo(Money.of(5, "KES"));
    }

    @Test
    void requiredConstructorArgumentsAreNullChecked() {
        UUID ref = UUID.randomUUID();
        Money amount = Money.of(1, "KES");
        assertThatThrownBy(() -> new Transfer(ID, null, SOURCE, DEST, amount,
                Money.zero("KES"), TransferState.CREATED, null, null, null, T0, T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Transfer(ID, ref, SOURCE, DEST, amount, Money.zero("KES"),
                null, null, null, null, T0, T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("state is required");
        assertThatThrownBy(() -> new Transfer(ID, ref, SOURCE, DEST, amount, Money.zero("KES"),
                TransferState.CREATED, null, null, null, null, T0))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("createdAt is required");
        assertThatThrownBy(() -> new Transfer(ID, ref, SOURCE, DEST, amount, Money.zero("KES"),
                TransferState.CREATED, null, null, null, T0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("updatedAt is required");
    }

    private static Transfer transfer() {
        return Transfer.instantiate(ID, UUID.randomUUID(), SOURCE, DEST, Money.of(25_000, "KES"),
                null, T0);
    }
}
