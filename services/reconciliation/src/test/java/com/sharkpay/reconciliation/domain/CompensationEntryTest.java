package com.sharkpay.reconciliation.domain;

import com.sharkpay.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The 4-eyes compensation entry (RB-7 steps 2–5): structural validation of
 * the draft (RB-7 compensation key shape, ≥ 2 legs, per-currency balance,
 * principals, reason bounds), exactly-once execution, rehydration.
 */
class CompensationEntryTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-01T11:00:00Z");
    private static final String BREAK_ID = "brk_0123456789abcdef0123456789abcdef";

    @Test
    void proposeCreatesAProposedEntryWithTheRb7Key() {
        CompensationEntry entry = CompensationEntry.propose("cmp_01", BREAK_ID, "honeycoin",
                CompensationEntry.KEY_PREFIX + BREAK_ID, "ops.alice", "settlement variance",
                legs(150_000, 150_000), null);

        assertThat(entry.id()).isEqualTo("cmp_01");
        assertThat(entry.breakId()).isEqualTo(BREAK_ID);
        assertThat(entry.state()).isEqualTo(CompensationEntry.CompensationState.PROPOSED);
        assertThat(entry.compensationKey()).isEqualTo("ops:adj:" + BREAK_ID);
        assertThat(entry.requester()).isEqualTo("ops.alice");
        assertThat(entry.approver()).isNull();
        assertThat(entry.ledgerEntryId()).isNull();
        assertThat(entry.executedAt()).isNull();
        assertThat(entry.ledgerReplay()).isFalse();
        assertThat(entry.reversesEntryId()).isNull();
        assertThat(entry.legs()).hasSize(2);
    }

    @Test
    void theCompensationKeyMustBeAnOpsAdjustmentKey() {
        assertThatThrownBy(() -> propose("adjust:" + BREAK_ID))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("compensation key must be a ledger ops key");
        assertThatThrownBy(() -> propose("ops:adj:xx"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ledger ops key");
        assertThatThrownBy(() -> propose("ops:adj:" + "x".repeat(121)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 128 characters");
        assertThatThrownBy(() -> propose(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ops key");
    }

    @Test
    void theDraftNeedsAtLeastTwoLegs() {
        assertThatThrownBy(() -> CompensationEntry.propose("cmp_01", BREAK_ID, "honeycoin",
                key(), "ops.alice", "r",
                List.of(new CompensationLeg("suspense:recon:KES", PostingDirection.DEBIT,
                        Money.of(1_000, "KES"))), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 2 legs");
        assertThatThrownBy(() -> CompensationEntry.propose("cmp_01", BREAK_ID, "honeycoin",
                key(), "ops.alice", "r", null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theDraftMustBalancePerCurrency() {
        // KES off by 500
        assertThatThrownBy(() -> CompensationEntry.propose("cmp_01", BREAK_ID, "honeycoin",
                key(), "ops.alice", "r", legs(150_000, 149_500), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must balance per currency")
                .hasMessageContaining("KES");

        // balanced across two currencies separately is fine: debit KES /
        // credit KES AND debit USD / credit USD
        CompensationEntry multiCurrency = CompensationEntry.propose("cmp_01", BREAK_ID,
                "honeycoin", key(), "ops.alice", "r",
                List.of(new CompensationLeg("suspense:recon:KES", PostingDirection.DEBIT,
                                Money.of(1_000, "KES")),
                        new CompensationLeg("honeycoin:settlement:KES", PostingDirection.CREDIT,
                                Money.of(1_000, "KES")),
                        new CompensationLeg("suspense:recon:USD", PostingDirection.DEBIT,
                                Money.of(75, "USD")),
                        new CompensationLeg("honeycoin:settlement:USD", PostingDirection.CREDIT,
                                Money.of(75, "USD"))),
                null);
        assertThat(multiCurrency.legs()).hasSize(4);

        // a KES imbalance is not cancelled by a USD imbalance
        assertThatThrownBy(() -> CompensationEntry.propose("cmp_01", BREAK_ID, "honeycoin",
                key(), "ops.alice", "r",
                List.of(new CompensationLeg("suspense:recon:KES", PostingDirection.DEBIT,
                                Money.of(1_000, "KES")),
                        new CompensationLeg("honeycoin:settlement:KES", PostingDirection.CREDIT,
                                Money.of(500, "KES")),
                        new CompensationLeg("suspense:recon:USD", PostingDirection.DEBIT,
                                Money.of(75, "USD")),
                        new CompensationLeg("honeycoin:settlement:USD", PostingDirection.CREDIT,
                                Money.of(25, "USD"))),
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("KES");
    }

    @Test
    void theDraftValidatesPrincipalsAndReason() {
        assertThatThrownBy(() -> CompensationEntry.propose("cmp_01", BREAK_ID, "honeycoin",
                key(), " ", "r", legs(1, 1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("requester must not be blank");
        assertThatThrownBy(() -> CompensationEntry.propose("cmp_01", BREAK_ID, "honeycoin",
                key(), "p".repeat(129), "r", legs(1, 1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 128");
        assertThatThrownBy(() -> CompensationEntry.propose("cmp_01", BREAK_ID, "honeycoin",
                key(), "ops.alice", null, legs(1, 1), null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> CompensationEntry.propose("cmp_01", BREAK_ID, "honeycoin",
                key(), "ops.alice", "  ", legs(1, 1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reason must not be blank");
        assertThatThrownBy(() -> CompensationEntry.propose("cmp_01", BREAK_ID, "honeycoin",
                key(), "ops.alice", "r".repeat(401), legs(1, 1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 400");
    }

    @Test
    void executeRecordsApprovalJournalEntryAndTimestamp() {
        CompensationEntry entry = propose(key());
        UUID ledgerEntryId = UUID.randomUUID();
        entry.execute("ops.bob", ledgerEntryId, false, T1);

        assertThat(entry.state()).isEqualTo(CompensationEntry.CompensationState.EXECUTED);
        assertThat(entry.approver()).isEqualTo("ops.bob");
        assertThat(entry.ledgerEntryId()).isEqualTo(ledgerEntryId);
        assertThat(entry.executedAt()).isEqualTo(T1);
        assertThat(entry.ledgerReplay()).isFalse();

        // a replay flag is recorded verbatim (still one posting in the ledger)
        CompensationEntry replayed = propose(key() + "#2");
        replayed.execute("ops.bob", UUID.randomUUID(), true, T1);
        assertThat(replayed.ledgerReplay()).isTrue();
    }

    @Test
    void aCompensationExecutesAtMostOnce() {
        CompensationEntry entry = propose(key());
        entry.execute("ops.bob", UUID.randomUUID(), false, T1);
        assertThatThrownBy(() -> entry.execute("ops.carol", UUID.randomUUID(), false, T1))
                .isInstanceOf(ReconciliationStateException.class)
                .hasMessageContaining("already executed")
                .hasMessageContaining("can never execute twice");
    }

    @Test
    void executeValidatesItsArguments() {
        CompensationEntry entry = propose(key());
        UUID ledgerEntryId = UUID.randomUUID();
        assertThatThrownBy(() -> entry.execute(null, ledgerEntryId, false, T1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("approver is required");
        assertThatThrownBy(() -> entry.execute(" ", ledgerEntryId, false, T1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> entry.execute("ops.bob", null, false, T1))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("ledgerEntryId is required");
        assertThatThrownBy(() -> entry.execute("ops.bob", ledgerEntryId, false, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("now is required");
    }

    @Test
    void compensationLegValidatesItsOwnShape() {
        assertThatThrownBy(() -> new CompensationLeg(null, PostingDirection.DEBIT,
                Money.of(1, "KES")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CompensationLeg(" ", PostingDirection.DEBIT,
                Money.of(1, "KES")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("accountRef must not be blank");
        assertThatThrownBy(() -> new CompensationLeg("a".repeat(129), PostingDirection.DEBIT,
                Money.of(1, "KES")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most 128");
        assertThatThrownBy(() -> new CompensationLeg("acct", null, Money.of(1, "KES")))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CompensationLeg("acct", PostingDirection.DEBIT, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CompensationLeg("acct", PostingDirection.DEBIT,
                Money.of(0, "KES")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount must be positive");
        assertThatThrownBy(() -> new CompensationLeg("acct", PostingDirection.DEBIT,
                Money.of(-5, "KES")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amount must be positive");

        // inverted convenience
        CompensationLeg leg = new CompensationLeg("suspense:recon:KES", PostingDirection.DEBIT,
                Money.of(1_000, "KES"));
        assertThat(leg.inverted().direction()).isEqualTo(PostingDirection.CREDIT);
        assertThat(leg.inverted().accountRef()).isEqualTo("suspense:recon:KES");
        assertThat(leg.inverted().amount()).isEqualTo(Money.of(1_000, "KES"));
    }

    @Test
    void postingDirectionWireSemantics() {
        assertThat(PostingDirection.DEBIT.wireName()).isEqualTo("debit");
        assertThat(PostingDirection.CREDIT.wireName()).isEqualTo("credit");
        assertThat(PostingDirection.DEBIT.opposite()).isEqualTo(PostingDirection.CREDIT);
        assertThat(PostingDirection.CREDIT.opposite()).isEqualTo(PostingDirection.DEBIT);
        assertThat(PostingDirection.fromWireName("credit")).isEqualTo(PostingDirection.CREDIT);
        assertThatThrownBy(() -> PostingDirection.fromWireName("side"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown posting direction");
    }

    @Test
    void compensationStateWireNamesAndParsing() {
        assertThat(CompensationEntry.CompensationState.PROPOSED.wireName()).isEqualTo("proposed");
        assertThat(CompensationEntry.CompensationState.EXECUTED.wireName()).isEqualTo("executed");
        assertThat(CompensationEntry.CompensationState.fromWireName("executed"))
                .isEqualTo(CompensationEntry.CompensationState.EXECUTED);
        assertThatThrownBy(() -> CompensationEntry.CompensationState.fromWireName("draft"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown compensation state");
    }

    @Test
    void rehydrateRestoresEveryFieldIncludingTheReversalReference() {
        UUID reverses = UUID.randomUUID();
        UUID ledgerEntryId = UUID.randomUUID();
        CompensationEntry entry = CompensationEntry.rehydrate("cmp_9", BREAK_ID, "honeycoin",
                key() + "#3", "ops.alice", "corrected wrong compensation", legs(1_000, 1_000),
                reverses, CompensationEntry.CompensationState.EXECUTED, "ops.bob", ledgerEntryId,
                T1, true);
        assertThat(entry.state()).isEqualTo(CompensationEntry.CompensationState.EXECUTED);
        assertThat(entry.reversesEntryId()).isEqualTo(reverses);
        assertThat(entry.ledgerEntryId()).isEqualTo(ledgerEntryId);
        assertThat(entry.ledgerReplay()).isTrue();
        assertThat(entry.approver()).isEqualTo("ops.bob");
        assertThat(entry.executedAt()).isEqualTo(T1);

        // rehydrating an executed entry is still frozen
        assertThatThrownBy(() -> entry.execute("ops.carol", UUID.randomUUID(), false, T1))
                .isInstanceOf(ReconciliationStateException.class);
    }

    private static String key() {
        return CompensationEntry.KEY_PREFIX + BREAK_ID;
    }

    private static CompensationEntry propose(String key) {
        return CompensationEntry.propose("cmp_01", BREAK_ID, "honeycoin", key, "ops.alice",
                "settlement variance", legs(150_000, 150_000), null);
    }

    private static List<CompensationLeg> legs(long debit, long credit) {
        return List.of(new CompensationLeg("suspense:recon:KES", PostingDirection.DEBIT,
                        Money.of(debit, "KES")),
                new CompensationLeg("honeycoin:settlement:KES", PostingDirection.CREDIT,
                        Money.of(credit, "KES")));
    }
}
