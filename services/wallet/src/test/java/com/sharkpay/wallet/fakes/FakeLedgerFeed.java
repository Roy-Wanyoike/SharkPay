package com.sharkpay.wallet.fakes;

import com.sharkpay.wallet.domain.Direction;
import com.sharkpay.wallet.domain.Source;
import com.sharkpay.wallet.ledger.LedgerPostingEvent;
import com.sharkpay.wallet.ports.LedgerEventConsumer;

import java.time.Instant;
import java.util.UUID;

/**
 * In-tree fake source of {@code ledger.posting.committed.v1} events: builds
 * well-formed, balanced journal entries that target a wallet's ledger
 * account and delivers them to the {@link LedgerEventConsumer} (the
 * projector). Doubles as the executable specification of the feed the real
 * NATS/Kafka binding must satisfy — including out-of-order and duplicate
 * delivery.
 */
public final class FakeLedgerFeed {

    /** The ledger's clearing account used as the counter-leg of test entries. */
    public static final UUID CLEARING_ACCOUNT = UUID.fromString("00000000-0000-0000-0000-00000000c1ea");

    private final LedgerEventConsumer consumer;
    private long nextPostingId = 10001L;

    public FakeLedgerFeed(LedgerEventConsumer consumer) {
        this.consumer = consumer;
    }

    /**
     * Builds and delivers a balanced 2-leg entry whose wallet leg has the
     * given direction and amount (credit = money in, debit = money out).
     */
    public LedgerPostingEvent commit(UUID walletAccountId, String currency, Direction walletDirection,
                                     long amountMinor, Source source, UUID sourceRef, String entryType) {
        LedgerPostingEvent event = entry(walletAccountId, currency, walletDirection,
                amountMinor, source, sourceRef, entryType, Instant.now());
        deliver(event);
        return event;
    }

    /** Builds (without delivering) a balanced 2-leg entry. */
    public LedgerPostingEvent entry(UUID walletAccountId, String currency, Direction walletDirection,
                                    long amountMinor, Source source, UUID sourceRef, String entryType,
                                    Instant occurredAt) {
        long walletPostingId = nextPostingId;
        long clearingPostingId = nextPostingId + 1;
        nextPostingId += 2;
        UUID entryId = UUID.randomUUID();
        UUID eventId = UUID.randomUUID();

        LedgerPostingEvent.Posting walletLeg = new LedgerPostingEvent.Posting(
                walletPostingId, walletAccountId, null, currency,
                walletDirection == Direction.DEBIT ? amountMinor : 0L,
                walletDirection == Direction.CREDIT ? amountMinor : 0L);
        LedgerPostingEvent.Posting clearingLeg = new LedgerPostingEvent.Posting(
                clearingPostingId, CLEARING_ACCOUNT, "provider:clearing:KES", currency,
                walletDirection == Direction.CREDIT ? amountMinor : 0L,
                walletDirection == Direction.DEBIT ? amountMinor : 0L);

        return new LedgerPostingEvent(eventId.toString(), LedgerPostingEvent.TYPE,
                LedgerPostingEvent.SPECVERSION, LedgerPostingEvent.SOURCE, entryId.toString(),
                occurredAt,
                new LedgerPostingEvent.LedgerData(entryId, "feed:" + entryId, source, sourceRef,
                        entryType, null, null, null, java.util.List.of(walletLeg, clearingLeg)));
    }

    /** Delivers an event (any order, any number of times). */
    public void deliver(LedgerPostingEvent event) {
        consumer.onLedgerPosting(event);
    }

    /** The next posting id this feed will hand out (test assertions). */
    public long nextPostingId() {
        return nextPostingId;
    }
}
