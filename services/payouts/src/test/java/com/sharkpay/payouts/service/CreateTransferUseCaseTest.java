package com.sharkpay.payouts.service;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.payouts.domain.IdempotencyConflictException;
import com.sharkpay.payouts.domain.InsufficientFundsException;
import com.sharkpay.payouts.domain.SameWalletException;
import com.sharkpay.payouts.domain.Transfer;
import com.sharkpay.payouts.domain.TransferState;
import com.sharkpay.payouts.domain.UnknownWalletException;
import com.sharkpay.payouts.domain.WalletFrozenException;
import com.sharkpay.payouts.events.TransferEvents;
import com.sharkpay.payouts.ports.LedgerPort;
import com.sharkpay.payouts.testsupport.PayoutsTestEnv;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * CreateTransferUseCase — G2 money safety: the whole wallet-to-wallet
 * movement is ONE atomic journal entry containing both legs (the fake
 * ledger enforces ≥ 2 legs, per-currency balance and key idempotency), so a
 * transfer is never partially posted. Idempotency-Key is required and
 * replay/conflict semantics are exact; pre-flight rejections create
 * nothing; a ledger business rejection terminates the transfer FAILED with
 * zero legs landed.
 */
class CreateTransferUseCaseTest {

    private final PayoutsTestEnv env = new PayoutsTestEnv();

    @Test
    void aHappyTransferPostsExactlyOneBalancedTwoLegEntryAndTerminatesSucceeded() {
        Transfer transfer = env.createTransfer("key-1", 25_000L);

        assertThat(transfer.state()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(transfer.entryId()).isNotNull();
        assertThat(transfer.fee()).isEqualTo(com.sharkpay.money.Money.zero("KES"));
        assertThat(transfer.sourceWalletId()).isEqualTo(PayoutsTestEnv.WALLET);
        assertThat(transfer.destinationWalletId()).isEqualTo(PayoutsTestEnv.OTHER_WALLET);

        // G2: the SINGLE atomic posting, both legs, nothing else in the journal
        assertThat(env.ledger.journal()).hasSize(1);
        assertIsTwoLegWalletPosting(transfer);
        assertThat(env.ledger.balanceOf(env.walletAccount.toString(), "KES"))
                .isEqualTo(PayoutsTestEnv.DEFAULT_BALANCE - 25_000);
        assertThat(env.ledger.balanceOf(env.otherWalletAccount.toString(), "KES"))
                .isEqualTo(25_000);
        // key + source_ref alignment
        var entry = env.ledger.entry("transfers:" + transfer.id()).orElseThrow();
        assertThat(entry.sourceRef()).isEqualTo(transfer.internalRef());
        assertThat(entry.entryType()).isEqualTo(LedgerPort.EntryType.CAPTURE);
        // the transfer is persisted with its audit trail
        assertThat(env.transfers.findById(transfer.id())).isPresent();
        assertThat(env.transfers.transitionsOf(transfer.id())).hasSize(1);
        // exactly one terminal event
        assertThat(env.events.eventsOfType(TransferEvents.SUCCEEDED)).hasSize(1);
    }

    @Test
    void theIdempotencyKeyReplaysTheOriginalTransferWithoutASecondPosting() {
        Transfer first = env.createTransfer("same-key", 10_000L);
        int journalBefore = env.ledger.journal().size();
        int eventsBefore = env.events.count();

        CreateTransferUseCase.Result replay = env.createTransfer.create("same-key",
                PayoutsTestEnv.WALLET, PayoutsTestEnv.OTHER_WALLET, 10_000L, "KES", Map.of());

        assertThat(replay.replay()).isTrue();
        assertThat(replay.transfer().id()).isEqualTo(first.id());
        assertThat(replay.transfer().entryId()).isEqualTo(first.entryId());
        assertThat(env.ledger.journal()).hasSize(journalBefore); // no second posting
        assertThat(env.events.count()).isEqualTo(eventsBefore);  // no second event
        assertThat(env.ledger.totalEffects()).isEqualTo(1);
    }

    @Test
    void theSameKeyWithADifferentPayloadIsA409Conflict() {
        env.createTransfer("conflict-key", 10_000L);
        assertThatThrownBy(() -> env.createTransfer.create("conflict-key",
                PayoutsTestEnv.WALLET, PayoutsTestEnv.OTHER_WALLET, 11_000L, "KES", Map.of()))
                .isInstanceOf(IdempotencyConflictException.class);
        // a different destination is also a different payload
        assertThatThrownBy(() -> env.createTransfer.create("conflict-key",
                PayoutsTestEnv.OTHER_WALLET, PayoutsTestEnv.WALLET, 10_000L, "KES", Map.of()))
                .isInstanceOf(IdempotencyConflictException.class);
    }

    @Test
    void aBlankIdempotencyKeyIsRejected() {
        for (String bad : new String[]{null, "", "   "}) {
            assertThatThrownBy(() -> env.createTransfer.create(bad, PayoutsTestEnv.WALLET,
                    PayoutsTestEnv.OTHER_WALLET, 1_000L, "KES", Map.of()))
                    .as("key %s", bad)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Idempotency-Key header must not be blank");
        }
    }

    @Test
    void anUnknownSourceOrDestinationWalletIsA404AndCreatesNothing() {
        assertThatThrownBy(() -> env.createTransfer.create("k1", "wal_0000000000000000000000000",
                PayoutsTestEnv.OTHER_WALLET, 1_000L, "KES", Map.of()))
                .isInstanceOf(UnknownWalletException.class)
                .hasMessageContaining("wal_0000000000000000000000000");
        assertThatThrownBy(() -> env.createTransfer.create("k2", PayoutsTestEnv.WALLET,
                "wal_0000000000000000000000000", 1_000L, "KES", Map.of()))
                .isInstanceOf(UnknownWalletException.class);
        assertThat(env.ledger.journal()).isEmpty();
        assertThat(env.transfers.count()).isZero();
        assertThat(env.events.count()).isZero();
    }

    @Test
    void aFrozenSourceOrDestinationWalletIsA422() {
        env.wallets.freeze(PayoutsTestEnv.WALLET);
        assertThatThrownBy(() -> env.createTransfer.create("k1", PayoutsTestEnv.WALLET,
                PayoutsTestEnv.OTHER_WALLET, 1_000L, "KES", Map.of()))
                .isInstanceOf(WalletFrozenException.class)
                .hasMessageContaining(PayoutsTestEnv.WALLET);
        assertThat(env.ledger.journal()).isEmpty();

        // re-activate the source, then freeze the destination
        env.wallets.addWallet(PayoutsTestEnv.WALLET, env.principalId, "KES",
                PayoutsTestEnv.DEFAULT_BALANCE, env.walletAccount);
        env.wallets.freeze(PayoutsTestEnv.OTHER_WALLET);
        assertThatThrownBy(() -> env.createTransfer.create("k2", PayoutsTestEnv.WALLET,
                PayoutsTestEnv.OTHER_WALLET, 1_000L, "KES", Map.of()))
                .isInstanceOf(WalletFrozenException.class)
                .hasMessageContaining(PayoutsTestEnv.OTHER_WALLET);
        assertThat(env.ledger.journal()).isEmpty();
    }

    @Test
    void aCurrencyMismatchAgainstEitherWalletIsA422() {
        assertThatThrownBy(() -> env.createTransfer.create("k1", PayoutsTestEnv.WALLET,
                PayoutsTestEnv.OTHER_WALLET, 1_000L, "USD", Map.of()))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThat(env.ledger.journal()).isEmpty();
    }

    @Test
    void theSameSourceAndDestinationWalletIsA422() {
        assertThatThrownBy(() -> env.createTransfer.create("k1", PayoutsTestEnv.WALLET,
                PayoutsTestEnv.WALLET, 1_000L, "KES", Map.of()))
                .isInstanceOf(SameWalletException.class)
                .hasMessageContaining(PayoutsTestEnv.WALLET);
        assertThat(env.ledger.journal()).isEmpty();
    }

    @Test
    void insufficientAvailableBalanceIsA422CarryingTheNumbers() {
        assertThatThrownBy(() -> env.createTransfer.create("k1", PayoutsTestEnv.WALLET,
                PayoutsTestEnv.OTHER_WALLET, PayoutsTestEnv.DEFAULT_BALANCE + 1, "KES", Map.of()))
                .isInstanceOf(InsufficientFundsException.class)
                .hasMessageContaining(String.valueOf(PayoutsTestEnv.DEFAULT_BALANCE));
        assertThat(env.ledger.journal()).isEmpty();
        // exactly the balance is fine
        Transfer exact = env.createTransfer.create("k2", PayoutsTestEnv.WALLET,
                PayoutsTestEnv.OTHER_WALLET, PayoutsTestEnv.DEFAULT_BALANCE, "KES", Map.of())
                .transfer();
        assertThat(exact.state()).isEqualTo(TransferState.SUCCEEDED);
    }

    @Test
    void aLedgerBusinessRejectionTerminatesTheTransferFailedWithNoLegLanded() {
        // the read side says the wallet is funded, the ledger authority says no —
        // the row-locked check is the authority
        env.ledger.rejectPrefix("transfers:");

        CreateTransferUseCase.Result result = env.createTransfer.create("k1",
                PayoutsTestEnv.WALLET, PayoutsTestEnv.OTHER_WALLET, 25_000L, "KES", Map.of());

        assertThat(result.transfer().state()).isEqualTo(TransferState.FAILED);
        assertThat(result.transfer().entryId()).isNull();
        assertThat(result.transfer().failureReason()).contains("insufficient_funds");
        // all-or-nothing: no journal row, no balance movement
        assertThat(env.ledger.journal()).isEmpty();
        assertThat(env.ledger.balanceOf(env.walletAccount.toString(), "KES"))
                .isEqualTo(PayoutsTestEnv.DEFAULT_BALANCE);
        // the terminal FAILED event is emitted exactly once
        assertThat(env.events.eventsOfType(TransferEvents.FAILED)).hasSize(1);
        assertThat(env.events.eventsOfType(TransferEvents.SUCCEEDED)).isEmpty();
        // and the idempotency record is kept: the retry replays the FAILED original
        CreateTransferUseCase.Result replay = env.createTransfer.create("k1",
                PayoutsTestEnv.WALLET, PayoutsTestEnv.OTHER_WALLET, 25_000L, "KES", Map.of());
        assertThat(replay.replay()).isTrue();
        assertThat(replay.transfer().state()).isEqualTo(TransferState.FAILED);
        assertThat(env.ledger.journal()).isEmpty();
    }

    @Test
    void aLedgerPortFailurePropagatesAndReleasesTheKeyReservation() {
        env.ledger.failPrefix("transfers:", new RuntimeException("ledger unreachable"));

        assertThatThrownBy(() -> env.createTransfer.create("k1", PayoutsTestEnv.WALLET,
                PayoutsTestEnv.OTHER_WALLET, 1_000L, "KES", Map.of()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("ledger unreachable");
        assertThat(env.ledger.journal()).isEmpty();
        assertThat(env.transfers.count()).isZero();
        assertThat(env.idempotency.count()).isZero(); // reservation released, safe to retry
    }

    @Test
    void aReplayWhoseOriginalTransferDisappearedSurfacesLoudly() {
        env.createTransfer("lost-key", 10_000L);
        env.transfers.remove(env.transfers.findById(
                env.idempotency.find(com.sharkpay.payouts.ports.IdempotencyStore.Scope
                        .CREATE_TRANSFER, "lost-key").orElseThrow().entityId()).orElseThrow().id());

        assertThatThrownBy(() -> env.createTransfer.create("lost-key", PayoutsTestEnv.WALLET,
                PayoutsTestEnv.OTHER_WALLET, 10_000L, "KES", Map.of()))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("is missing");
    }

    @Test
    void theResultRecordDemandsATerminalTransfer() {
        Transfer created = com.sharkpay.payouts.domain.Transfer.instantiate(
                "trf_0123456789abcdef0123456789abcdef", java.util.UUID.randomUUID(),
                PayoutsTestEnv.WALLET, PayoutsTestEnv.OTHER_WALLET,
                com.sharkpay.money.Money.of(1, "KES"), null, env.clock.instant());
        assertThatThrownBy(() -> new CreateTransferUseCase.Result(created, false))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("must be terminal");
    }

    /** G2: the single posting is exactly the two wallet legs of the move. */
    private void assertIsTwoLegWalletPosting(Transfer transfer) {
        var legs = env.ledger.legsOf("transfers:" + transfer.id());
        assertThat(legs).hasSize(2);
        assertThat(legs.get(0).accountRef()).isEqualTo(env.walletAccount.toString());
        assertThat(legs.get(0).direction()).isEqualTo(LedgerPort.Direction.DEBIT);
        assertThat(legs.get(0).amount()).isEqualTo(transfer.amount());
        assertThat(legs.get(1).accountRef()).isEqualTo(env.otherWalletAccount.toString());
        assertThat(legs.get(1).direction()).isEqualTo(LedgerPort.Direction.CREDIT);
        assertThat(legs.get(1).amount()).isEqualTo(transfer.amount());
    }
}
