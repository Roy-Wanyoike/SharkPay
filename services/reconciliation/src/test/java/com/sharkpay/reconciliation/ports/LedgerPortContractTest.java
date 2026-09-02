package com.sharkpay.reconciliation.ports;

import com.sharkpay.money.Money;
import com.sharkpay.reconciliation.fakes.FakeLedgerPort;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The LedgerPort's own contract — the executable specification of the Go
 * ledger's internal transactions API (services/ledger/internal/api/server.go):
 * the posting record's structural rules (≥ 2 legs, key belongs to the
 * entry's source, bounded key/reason, reversal pairing shape) and the leg
 * vocabulary, plus the in-tree fake's enforcement of the wire-level
 * invariants (per-currency balance, idempotency on (source, key), reversal
 * pairing against committed entries). The domain CompensationLeg → port Leg
 * mapping is exercised by the approve/execute use-case tests.
 */
class LedgerPortContractTest {

    private static final UUID SOURCE_REF = UUID.randomUUID();

    @Test
    void aJournalEntryNeedsAtLeastTwoLegs() {
        assertThatThrownBy(() -> LedgerPort.LedgerPosting.of("ops:adj:brk_x",
                LedgerPort.Source.OPS, SOURCE_REF, LedgerPort.EntryType.ADJUSTMENT, "r",
                List.of(leg(500))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 2 postings");
        assertThatThrownBy(() -> LedgerPort.LedgerPosting.of("ops:adj:brk_x",
                LedgerPort.Source.OPS, SOURCE_REF, LedgerPort.EntryType.ADJUSTMENT, "r",
                List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theTransactionKeyMustBelongToTheSourceAndStayBounded() {
        // the key is the idempotency key: it must start with the source
        assertThatThrownBy(() -> LedgerPort.LedgerPosting.of("payments:pay_x",
                LedgerPort.Source.OPS, SOURCE_REF, LedgerPort.EntryType.ADJUSTMENT, "r",
                legs(500, 500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must start with source ops");
        // bounded length (5–128): "ops:" is 4 chars
        assertThatThrownBy(() -> LedgerPort.LedgerPosting.of("ops:",
                LedgerPort.Source.OPS, SOURCE_REF, LedgerPort.EntryType.ADJUSTMENT, "r",
                legs(500, 500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5-128 characters");
        // "ops:x" is exactly 5 chars — the minimum legal length
        assertThat(LedgerPort.LedgerPosting.of("ops:x",
                LedgerPort.Source.OPS, SOURCE_REF, LedgerPort.EntryType.ADJUSTMENT, "r",
                legs(500, 500)).transactionKey()).isEqualTo("ops:x");
        assertThatThrownBy(() -> LedgerPort.LedgerPosting.of("ops:" + "x".repeat(130),
                LedgerPort.Source.OPS, SOURCE_REF, LedgerPort.EntryType.ADJUSTMENT, "r",
                legs(500, 500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("5-128 characters");
        // bounded reason
        assertThatThrownBy(() -> LedgerPort.LedgerPosting.of("ops:adj:brk_x",
                LedgerPort.Source.OPS, SOURCE_REF, LedgerPort.EntryType.ADJUSTMENT,
                "r".repeat(501), legs(500, 500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 500 characters");
        // a null reason is fine (the ledger treats it as absent)
        assertThat(LedgerPort.LedgerPosting.of("ops:adj:brk_x", LedgerPort.Source.OPS,
                SOURCE_REF, LedgerPort.EntryType.ADJUSTMENT, null, legs(500, 500)).reason())
                .isNull();
        // nulls rejected
        assertThatThrownBy(() -> LedgerPort.LedgerPosting.of(null,
                LedgerPort.Source.OPS, SOURCE_REF, LedgerPort.EntryType.ADJUSTMENT, "r",
                legs(500, 500)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("transactionKey is required");
        assertThatThrownBy(() -> LedgerPort.LedgerPosting.of("ops:adj:brk_x", null,
                SOURCE_REF, LedgerPort.EntryType.ADJUSTMENT, "r", legs(500, 500)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("source is required");
        assertThatThrownBy(() -> LedgerPort.LedgerPosting.of("ops:adj:brk_x",
                LedgerPort.Source.OPS, null, LedgerPort.EntryType.ADJUSTMENT, "r", legs(500, 500)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sourceRef is required");
        assertThatThrownBy(() -> LedgerPort.LedgerPosting.of("ops:adj:brk_x",
                LedgerPort.Source.OPS, SOURCE_REF, null, "r", legs(500, 500)))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("entryType is required");
        assertThatThrownBy(() -> LedgerPort.LedgerPosting.of("ops:adj:brk_x",
                LedgerPort.Source.OPS, SOURCE_REF, LedgerPort.EntryType.ADJUSTMENT, "r", null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("legs is required");
    }

    @Test
    void reversalEntriesMustReferenceTheEntryTheyCompensate() {
        assertThatThrownBy(() -> LedgerPort.LedgerPosting.of("ops:adj:brk_x",
                LedgerPort.Source.OPS, SOURCE_REF, LedgerPort.EntryType.REVERSAL, "r",
                legs(500, 500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reversal entries must reference the entry they compensate");

        // and only reversals may carry the reference (never a silent mutation
        // of history, DATA-MODEL §4.4/4.5)
        assertThatThrownBy(() -> new LedgerPort.LedgerPosting("ops:adj:brk_x",
                LedgerPort.Source.OPS, SOURCE_REF, LedgerPort.EntryType.ADJUSTMENT,
                UUID.randomUUID(), "r", legs(500, 500)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reverses_entry_id is only allowed on reversal entries");

        // the legal shape round-trips its reference
        UUID reverses = UUID.randomUUID();
        LedgerPort.LedgerPosting reversal = LedgerPort.LedgerPosting.reversalOf("ops:adj:brk_x#2",
                LedgerPort.Source.OPS, SOURCE_REF, reverses, "r", legs(500, 500));
        assertThat(reversal.entryType()).isEqualTo(LedgerPort.EntryType.REVERSAL);
        assertThat(reversal.reversesEntryId()).isEqualTo(reverses);
    }

    @Test
    void aLegValidatesItsOwnShapeAndInvertsForReversals() {
        assertThatThrownBy(() -> new LedgerPort.Leg(null, LedgerPort.Direction.DEBIT,
                Money.of(1, "KES")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LedgerPort.Leg(" ", LedgerPort.Direction.DEBIT,
                Money.of(1, "KES")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountRef must not be blank");
        assertThatThrownBy(() -> new LedgerPort.Leg("acct", null, Money.of(1, "KES")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LedgerPort.Leg("acct", LedgerPort.Direction.DEBIT, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LedgerPort.Leg("acct", LedgerPort.Direction.DEBIT,
                Money.of(0, "KES")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("leg amount must be positive");

        LedgerPort.Leg leg = leg(1_000);
        assertThat(leg.inverted().accountRef()).isEqualTo(leg.accountRef());
        assertThat(leg.inverted().direction()).isEqualTo(LedgerPort.Direction.CREDIT);
        assertThat(leg.inverted().amount()).isEqualTo(leg.amount());
        assertThat(leg.inverted().inverted().direction()).isEqualTo(LedgerPort.Direction.DEBIT);
    }

    @Test
    void theVocabularyCarriesTheLedgerWireNames() {
        // entry types (the ledger entry_type column)
        assertThat(LedgerPort.EntryType.CAPTURE.wireName()).isEqualTo("capture");
        assertThat(LedgerPort.EntryType.HOLD.wireName()).isEqualTo("hold");
        assertThat(LedgerPort.EntryType.RELEASE.wireName()).isEqualTo("release");
        assertThat(LedgerPort.EntryType.REVERSAL.wireName()).isEqualTo("reversal");
        assertThat(LedgerPort.EntryType.FEE.wireName()).isEqualTo("fee");
        assertThat(LedgerPort.EntryType.FX.wireName()).isEqualTo("fx");
        assertThat(LedgerPort.EntryType.ADJUSTMENT.wireName()).isEqualTo("adjustment");
        // sources (keys must start with these)
        assertThat(LedgerPort.Source.PAYMENTS.wireName()).isEqualTo("payments");
        assertThat(LedgerPort.Source.PAYOUTS.wireName()).isEqualTo("payouts");
        assertThat(LedgerPort.Source.TRANSFERS.wireName()).isEqualTo("transfers");
        assertThat(LedgerPort.Source.FX.wireName()).isEqualTo("fx");
        assertThat(LedgerPort.Source.FEES.wireName()).isEqualTo("fees");
        assertThat(LedgerPort.Source.OPS.wireName()).isEqualTo("ops");
        // directions
        assertThat(LedgerPort.Direction.DEBIT.wireName()).isEqualTo("debit");
        assertThat(LedgerPort.Direction.CREDIT.wireName()).isEqualTo("credit");
        assertThat(LedgerPort.Direction.DEBIT.opposite()).isEqualTo(LedgerPort.Direction.CREDIT);
        assertThat(LedgerPort.Direction.CREDIT.opposite()).isEqualTo(LedgerPort.Direction.DEBIT);
    }

    @Test
    void theFakeEnforcesPerCurrencyBalance() {
        FakeLedgerPort ledger = new FakeLedgerPort();

        LedgerPort.PostingResult offBy500 = ledger.post(LedgerPort.LedgerPosting.of(
                "ops:adj:brk_x", LedgerPort.Source.OPS, SOURCE_REF,
                LedgerPort.EntryType.ADJUSTMENT, "r", legs(1_000, 500)));
        assertThat(offBy500).isInstanceOf(LedgerPort.PostingResult.Rejected.class);
        assertThat(((LedgerPort.PostingResult.Rejected) offBy500).code())
                .isEqualTo("unbalanced_entry");
        assertThat(((LedgerPort.PostingResult.Rejected) offBy500).reason())
                .contains("off by 500")
                .contains("KES");
        assertThat(ledger.committedCount()).isZero();
        assertThat(ledger.attempts()).isEqualTo(1);

        // per-CURRENCY balance: KES balanced + USD balanced commits
        LedgerPort.PostingResult multiCurrency = ledger.post(LedgerPort.LedgerPosting.of(
                "ops:adj:brk_y", LedgerPort.Source.OPS, SOURCE_REF,
                LedgerPort.EntryType.ADJUSTMENT, "r", List.of(
                        new LedgerPort.Leg("suspense:recon:KES", LedgerPort.Direction.DEBIT,
                                Money.of(1_000, "KES")),
                        new LedgerPort.Leg("honeycoin:settlement:KES", LedgerPort.Direction.CREDIT,
                                Money.of(1_000, "KES")),
                        new LedgerPort.Leg("suspense:recon:USD", LedgerPort.Direction.DEBIT,
                                Money.of(75, "USD")),
                        new LedgerPort.Leg("honeycoin:settlement:USD", LedgerPort.Direction.CREDIT,
                                Money.of(75, "USD")))));
        assertThat(multiCurrency).isInstanceOf(LedgerPort.PostingResult.Committed.class);
        assertThat(ledger.committedCount()).isEqualTo(1);
    }

    @Test
    void theFakeIsIdempotentOnTheSourceAndKey() {
        FakeLedgerPort ledger = new FakeLedgerPort();

        LedgerPort.PostingResult first = ledger.post(LedgerPort.LedgerPosting.of(
                "ops:adj:brk_x", LedgerPort.Source.OPS, SOURCE_REF,
                LedgerPort.EntryType.ADJUSTMENT, "r", legs(500, 500)));
        UUID entryId = ((LedgerPort.PostingResult.Committed) first).entryId();
        assertThat(((LedgerPort.PostingResult.Committed) first).replay()).isFalse();

        // the same (source, key) replays the ORIGINAL entry id, posting nothing
        LedgerPort.PostingResult retry = ledger.post(LedgerPort.LedgerPosting.of(
                "ops:adj:brk_x", LedgerPort.Source.OPS, UUID.randomUUID(),
                LedgerPort.EntryType.ADJUSTMENT, "retry", legs(500, 500)));
        assertThat(((LedgerPort.PostingResult.Committed) retry).entryId()).isEqualTo(entryId);
        assertThat(((LedgerPort.PostingResult.Committed) retry).replay()).isTrue();

        assertThat(ledger.attempts()).isEqualTo(2);          // two posts attempted
        assertThat(ledger.committedCount()).isEqualTo(1);    // one journal entry
        assertThat(ledger.hasCommitted("ops:adj:brk_x")).isTrue();
        assertThat(ledger.entryIdOf("ops:adj:brk_x")).isEqualTo(entryId);
        assertThat(ledger.entryIdOf("ops:adj:never_posted")).isNull();

        // a different source with the same raw key is a DIFFERENT ledger key
        // (source is part of the idempotency tuple)
        LedgerPort.PostingResult otherSource = ledger.post(LedgerPort.LedgerPosting.of(
                "payouts:adj:brk_x", LedgerPort.Source.PAYOUTS, SOURCE_REF,
                LedgerPort.EntryType.ADJUSTMENT, "r", legs(500, 500)));
        assertThat(otherSource).isInstanceOf(LedgerPort.PostingResult.Committed.class);
        assertThat(((LedgerPort.PostingResult.Committed) otherSource).replay()).isFalse();
        assertThat(ledger.committedCount()).isEqualTo(2);
    }

    @Test
    void theFakeRejectsReversalsOfEntriesItNeverCommitted() {
        FakeLedgerPort ledger = new FakeLedgerPort();

        LedgerPort.PostingResult orphan = ledger.post(LedgerPort.LedgerPosting.reversalOf(
                "ops:adj:brk_rev", LedgerPort.Source.OPS, SOURCE_REF, UUID.randomUUID(), "r",
                legs(500, 500)));
        assertThat(orphan).isInstanceOf(LedgerPort.PostingResult.Rejected.class);
        assertThat(((LedgerPort.PostingResult.Rejected) orphan).code())
                .isEqualTo("reversal_mismatch");

        // pairing against a committed entry works
        UUID committed = ((LedgerPort.PostingResult.Committed) ledger.post(
                LedgerPort.LedgerPosting.of("ops:adj:brk_orig", LedgerPort.Source.OPS, SOURCE_REF,
                        LedgerPort.EntryType.ADJUSTMENT, "r", legs(500, 500)))).entryId();
        LedgerPort.PostingResult reversal = ledger.post(LedgerPort.LedgerPosting.reversalOf(
                "ops:adj:brk_rev", LedgerPort.Source.OPS, SOURCE_REF, committed, "r",
                legs(500, 500)));
        assertThat(reversal).isInstanceOf(LedgerPort.PostingResult.Committed.class);
    }

    @Test
    void theFakeRejectNextKnobIsOneShotAndRecorded() {
        FakeLedgerPort ledger = new FakeLedgerPort();
        ledger.rejectNext("insufficient_funds", "wallet below zero under row locks");

        LedgerPort.PostingResult rejected = ledger.post(LedgerPort.LedgerPosting.of(
                "ops:adj:brk_x", LedgerPort.Source.OPS, SOURCE_REF,
                LedgerPort.EntryType.ADJUSTMENT, "r", legs(500, 500)));
        assertThat(rejected).isInstanceOf(LedgerPort.PostingResult.Rejected.class);
        assertThat(ledger.rejections()).hasSize(1);
        assertThat(ledger.rejections().get(0).code()).isEqualTo("insufficient_funds");
        assertThat(ledger.rejections().get(0).reason()).contains("row locks");
        assertThat(ledger.committedCount()).isZero();

        // the next post is business as usual (operators retry the same entry)
        LedgerPort.PostingResult retry = ledger.post(LedgerPort.LedgerPosting.of(
                "ops:adj:brk_x", LedgerPort.Source.OPS, SOURCE_REF,
                LedgerPort.EntryType.ADJUSTMENT, "r", legs(500, 500)));
        assertThat(retry).isInstanceOf(LedgerPort.PostingResult.Committed.class);
        assertThat(ledger.committedCount()).isEqualTo(1);
        assertThat(ledger.rejections()).hasSize(1);
    }

    @Test
    void storedIdempotencyRecordsValidateTheirFields() {
        assertThatThrownBy(() -> new IdempotencyStore.StoredRequest(" ", "run_01"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requestFingerprint is required");
        assertThatThrownBy(() -> new IdempotencyStore.StoredRequest("TRIGGER_RUN|x", " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entityId is required");
        assertThatThrownBy(() -> new IdempotencyStore.StoredRequest(null, "run_01"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new IdempotencyStore.StoredRequest("TRIGGER_RUN|x", null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new IdempotencyStore.StoredRequest("TRIGGER_RUN|x", "run_01").entityId())
                .isEqualTo("run_01");
    }

    private static LedgerPort.Leg leg(long amountMinor) {
        return new LedgerPort.Leg("suspense:recon:KES", LedgerPort.Direction.DEBIT,
                Money.of(amountMinor, "KES"));
    }

    private static List<LedgerPort.Leg> legs(long debit, long credit) {
        return List.of(
                new LedgerPort.Leg("suspense:recon:KES", LedgerPort.Direction.DEBIT,
                        Money.of(debit, "KES")),
                new LedgerPort.Leg("honeycoin:settlement:KES", LedgerPort.Direction.CREDIT,
                        Money.of(credit, "KES")));
    }
}
