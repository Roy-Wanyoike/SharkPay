package com.sharkpay.wallet.service;

import com.sharkpay.money.Money;
import com.sharkpay.wallet.domain.Direction;
import com.sharkpay.wallet.domain.ProjectionInconsistencyException;
import com.sharkpay.wallet.domain.Source;
import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.events.WalletEvents;
import com.sharkpay.wallet.fakes.FakeLedgerFeed;
import com.sharkpay.wallet.ledger.LedgerPostingEvent;
import com.sharkpay.wallet.testsupport.WalletTestEnv;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The ledger balance projection (ADR 003 gate G2, mandatory):
 * consumes {@code ledger.posting.committed.v1} events into wallet total
 * balances idempotently (event-id AND leg-level dedup), ordered
 * (posting-id order — out-of-order delivery converges) and duplicate-safe.
 */
class ApplyLedgerEventUseCaseTest {

    private final WalletTestEnv env = new WalletTestEnv();

    @Test
    void committedEventsProjectIntoTotalBalances() {
        Wallet wallet = env.newWallet("KES");
        env.events.reset();

        env.credit(wallet, 150_000);
        env.debit(wallet, 50_000);

        assertThat(env.balanceReader.balancesOf(wallet).total())
                .isEqualTo(Money.of(100_000, "KES"));
        assertThat(env.projections.sequence(wallet.id()).size()).isEqualTo(2);
    }

    @Test
    void eachAppliedEventPublishesExactlyOneBalanceChangedPerWallet() {
        Wallet wallet = env.newWallet("KES");
        env.events.reset();
        UUID sourceRef = UUID.randomUUID();

        env.feed.commit(env.accountOf(wallet), "KES", Direction.CREDIT, 1_000,
                Source.PAYMENTS, sourceRef, "capture");

        assertThat(env.events.eventsOfType(WalletEvents.BALANCE_CHANGED)).hasSize(1);
        WalletEvents.WalletBalanceData data = (WalletEvents.WalletBalanceData)
                env.events.eventsOfType(WalletEvents.BALANCE_CHANGED).get(0).data();
        assertThat(data.wallet_id()).isEqualTo(wallet.id());
        assertThat(data.source()).isEqualTo(Source.PAYMENTS);
        assertThat(data.source_ref()).isEqualTo(sourceRef);
        assertThat(data.balances().available().amount_minor()).isEqualTo(1_000);
        assertThat(data.balances().held().amount_minor()).isZero();
    }

    @Test
    void exactRedeliveryOfAnEventIsANoOp() {
        Wallet wallet = env.newWallet("KES");
        LedgerPostingEvent event = env.credit(wallet, 100_000);
        env.events.reset();

        env.feed.deliver(event);   // exact duplicate (same event id)

        assertThat(env.balanceReader.balancesOf(wallet).total())
                .isEqualTo(Money.of(100_000, "KES"));   // not 200_000
        assertThat(env.projections.sequence(wallet.id()).size()).isEqualTo(1);
        assertThat(env.events.events()).isEmpty();   // no second balance.changed
    }

    @Test
    void aDifferentEventIdCarryingAnAlreadyProjectedLegDedupesAtLegLevel() {
        Wallet wallet = env.newWallet("KES");
        LedgerPostingEvent original = env.feed.entry(env.accountOf(wallet), "KES",
                Direction.CREDIT, 100_000, Source.PAYMENTS, UUID.randomUUID(), "capture",
                Instant.parse("2026-09-01T10:00:02Z"));
        env.feed.deliver(original);
        env.events.reset();

        // same posting ids, different event id: the leg is already projected
        LedgerPostingEvent sameLegsNewId = new LedgerPostingEvent(
                UUID.randomUUID().toString(), original.type(), original.specversion(),
                original.source(), original.subject(), original.occurred_at(), original.data());
        env.feed.deliver(sameLegsNewId);

        assertThat(env.balanceReader.balancesOf(wallet).total())
                .isEqualTo(Money.of(100_000, "KES"));   // never double-applied
        assertThat(env.projections.sequence(wallet.id()).size()).isEqualTo(1);
        // the new event id is still recorded as applied …
        assertThat(env.projections.isEventApplied(sameLegsNewId.id())).isTrue();
        // … but no balance.changed: nothing changed
        assertThat(env.events.events()).isEmpty();
    }

    @Test
    void outOfOrderDeliveryConvergesToTheInOrderProjection() {
        Wallet wallet = env.newWallet("KES");
        UUID account = env.accountOf(wallet);

        // three entries, delivered deliberately out of posting order; the
        // debit (posting 3rd) is delivered last so the fold never dips below
        // zero while earlier legs are missing
        LedgerPostingEvent credit1000 = env.feed.entry(account, "KES", Direction.CREDIT,
                1_000, Source.PAYMENTS, UUID.randomUUID(), "capture",
                Instant.parse("2026-09-01T10:00:02Z"));
        LedgerPostingEvent credit200 = env.feed.entry(account, "KES", Direction.CREDIT,
                200, Source.FX, UUID.randomUUID(), "fx",
                Instant.parse("2026-09-01T10:00:04Z"));
        LedgerPostingEvent debit300 = env.feed.entry(account, "KES", Direction.DEBIT,
                300, Source.PAYOUTS, UUID.randomUUID(), "hold",
                Instant.parse("2026-09-01T10:00:06Z"));

        env.feed.deliver(credit200);    // future leg first
        assertThat(env.balanceReader.balancesOf(wallet).total()).isEqualTo(Money.of(200, "KES"));

        env.feed.deliver(credit1000);   // earlier leg second
        env.feed.deliver(debit300);     // the debit last

        // converged: exactly the in-order projection
        assertThat(env.balanceReader.balancesOf(wallet).total()).isEqualTo(Money.of(900, "KES"));
        assertThat(env.projections.statement(wallet.id(), 10, null))
                .extracting(line -> line.leg().postingId())
                .containsExactly(10001L, 10003L, 10005L);
        assertThat(env.projections.statement(wallet.id(), 10, null))
                .extracting(line -> line.balanceAfter().amountMinor())
                .containsExactly(1_000L, 1_200L, 900L);
    }

    @Test
    void legsOfOtherAccountsAreIgnoredButTheEventIsMarkedApplied() {
        Wallet wallet = env.newWallet("KES");

        // an entry between two non-wallet accounts (provider <-> clearing)
        LedgerPostingEvent nonWallet = env.feed.entry(FakeLedgerFeed.CLEARING_ACCOUNT, "KES",
                Direction.DEBIT, 5_000, Source.FEES, UUID.randomUUID(), "fee",
                Instant.parse("2026-09-01T10:00:02Z"));
        env.feed.deliver(nonWallet);

        assertThat(env.balanceReader.balancesOf(wallet).total()).isEqualTo(Money.zero("KES"));
        assertThat(env.projections.projectedWalletCount()).isZero();
        assertThat(env.projections.isEventApplied(nonWallet.id())).isTrue();
    }

    @Test
    void aLegWhoseCurrencyDoesNotMatchTheWalletIsRejectedWhole() {
        Wallet wallet = env.newWallet("KES");

        // direct delivery of the mismatched event rejects it for dead-lettering
        LedgerPostingEvent mismatched = env.feed.entry(env.accountOf(wallet), "USD",
                Direction.CREDIT, 100, Source.PAYMENTS, UUID.randomUUID(), "capture",
                Instant.parse("2026-09-01T10:00:02Z"));
        assertThatThrownBy(() -> env.feed.deliver(mismatched))
                .isInstanceOf(ProjectionInconsistencyException.class)
                .hasMessageContaining("currency");
        // nothing applied, event not marked applied → redelivery after repair
        assertThat(env.balanceReader.balancesOf(wallet).total()).isEqualTo(Money.zero("KES"));
        assertThat(env.projections.isEventApplied(mismatched.id())).isFalse();
    }

    @Test
    void aLegThatWouldProjectANegativeBalanceIsRejectedWhole() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 100);

        LedgerPostingEvent tooBig = env.feed.entry(env.accountOf(wallet), "KES",
                Direction.DEBIT, 101, Source.PAYOUTS, UUID.randomUUID(), "hold",
                Instant.parse("2026-09-01T10:00:02Z"));
        assertThatThrownBy(() -> env.feed.deliver(tooBig))
                .isInstanceOf(ProjectionInconsistencyException.class)
                .hasMessageContaining("never go negative");

        // no partial state: total unchanged, event not marked applied
        assertThat(env.balanceReader.balancesOf(wallet).total()).isEqualTo(Money.of(100, "KES"));
        assertThat(env.projections.isEventApplied(tooBig.id())).isFalse();
        // a corrected (smaller) debit with a NEW event id applies cleanly
        env.debit(wallet, 60);
        assertThat(env.balanceReader.balancesOf(wallet).total()).isEqualTo(Money.of(40, "KES"));
    }

    @Test
    void rejectedEventIdsAreRetryableAndThenConverge() {
        Wallet wallet = env.newWallet("KES");
        // in posting order the pair is valid: credit 100 (posting 10001)
        // then debit 40 (posting 10003) — never negative
        LedgerPostingEvent funding = env.feed.entry(env.accountOf(wallet), "KES",
                Direction.CREDIT, 100, Source.PAYMENTS, UUID.randomUUID(), "capture",
                Instant.parse("2026-09-01T10:00:02Z"));
        LedgerPostingEvent debit = env.feed.entry(env.accountOf(wallet), "KES",
                Direction.DEBIT, 40, Source.PAYOUTS, UUID.randomUUID(), "hold",
                Instant.parse("2026-09-01T10:00:06Z"));
        // … but the debit is delivered first: its fold alone dips below zero
        assertThatThrownBy(() -> env.feed.deliver(debit))
                .isInstanceOf(ProjectionInconsistencyException.class);
        assertThat(env.projections.isEventApplied(debit.id())).isFalse();

        // the funding credit lands, then redelivery of the same debit event
        // applies cleanly — the projection converges
        env.feed.deliver(funding);
        env.feed.deliver(debit);

        assertThat(env.balanceReader.balancesOf(wallet).total()).isEqualTo(Money.of(60, "KES"));
        assertThat(env.projections.isEventApplied(debit.id())).isTrue();
    }

    @Test
    void malformedEnvelopesAreRejectedBeforeTouchingTheProjection() {
        Wallet wallet = env.newWallet("KES");

        LedgerPostingEvent event = env.feed.entry(env.accountOf(wallet), "KES",
                Direction.CREDIT, 100, Source.PAYMENTS, UUID.randomUUID(), "capture",
                Instant.parse("2026-09-01T10:00:02Z"));
        LedgerPostingEvent wrongSource = new LedgerPostingEvent(event.id(), event.type(),
                event.specversion(), "sharkpay/wallet", event.subject(), event.occurred_at(),
                event.data());
        assertThatThrownBy(() -> env.feed.deliver(wrongSource))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");

        assertThat(env.projections.projectedWalletCount()).isZero();
        assertThat(env.projections.isEventApplied(wrongSource.id())).isFalse();
    }

    @Test
    void multipleWalletsInOneEntryAreEachPublishedOnce() {
        // a transfer entry: 300 moves from one wallet's account to the other's
        Wallet from = env.newWallet("KES");
        Wallet to = env.newWallet("KES");
        env.credit(from, 300);
        env.events.reset();

        UUID entryId = UUID.randomUUID();
        UUID sourceRef = UUID.randomUUID();
        LedgerPostingEvent.Posting[] legs = {
                new LedgerPostingEvent.Posting(20_001L, env.accountOf(from), null, "KES", 300, 0),
                new LedgerPostingEvent.Posting(20_002L, env.accountOf(to), null, "KES", 0, 300)
        };
        env.feed.deliver(new LedgerPostingEvent(UUID.randomUUID().toString(),
                LedgerPostingEvent.TYPE, LedgerPostingEvent.SPECVERSION,
                LedgerPostingEvent.SOURCE, entryId.toString(),
                Instant.parse("2026-09-01T10:00:02Z"),
                new LedgerPostingEvent.LedgerData(entryId, "transfers:" + sourceRef,
                        Source.TRANSFERS, sourceRef, "capture", null, null, null,
                        java.util.List.of(legs))));

        assertThat(env.balanceReader.balancesOf(from).total()).isEqualTo(Money.zero("KES"));
        assertThat(env.balanceReader.balancesOf(to).total()).isEqualTo(Money.of(300, "KES"));
        // one balance.changed per wallet whose projection changed
        assertThat(env.events.eventsOfType(WalletEvents.BALANCE_CHANGED)).hasSize(2);
    }

    @Test
    void overflowIsRejectedInsteadOfWrapping() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, Long.MAX_VALUE);

        LedgerPostingEvent oneMore = env.feed.entry(env.accountOf(wallet), "KES",
                Direction.CREDIT, 1, Source.PAYMENTS, UUID.randomUUID(), "capture",
                Instant.parse("2026-09-01T10:00:02Z"));
        assertThatThrownBy(() -> env.feed.deliver(oneMore))
                .isInstanceOf(ProjectionInconsistencyException.class)
                .hasMessageContaining("overflows int64");
        assertThat(env.balanceReader.balancesOf(wallet).total().amountMinor())
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void nullEventIsRejected() {
        assertThatThrownBy(() -> env.ledgerConsumer.onLedgerPosting(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("event is required");
    }
}
