package com.sharkpay.wallet.ports;

import com.sharkpay.wallet.ledger.LedgerPostingEvent;

/**
 * Port: the inbound {@code ledger.posting.committed.v1} feed. The projector
 * use case implements this interface; the real NATS/Kafka binding (and the
 * dev HTTP endpoint) call {@link #onLedgerPosting(LedgerPostingEvent)} for
 * every delivered event, in whatever order the transport delivers them —
 * the projection is idempotent and posting-ordered, so redelivery and
 * out-of-order arrival converge.
 */
public interface LedgerEventConsumer {

    void onLedgerPosting(LedgerPostingEvent event);
}
