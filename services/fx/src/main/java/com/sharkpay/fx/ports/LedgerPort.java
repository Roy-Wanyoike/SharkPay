package com.sharkpay.fx.ports;

import com.sharkpay.fx.domain.Leg;

import java.util.List;

/**
 * Consumer-driven port to the Go ledger service (the only writer of
 * journal entries — docs/ARCHITECTURE.md &#167;2 hard rule 2). The current
 * implementation is the fail-fast integration-pending placeholder in
 * {@code com.sharkpay.fx.config} until the REST adapter against the ledger's
 * internal idempotent posting API lands at integration; local tests use the
 * in-tree fake in {@code com.sharkpay.fx.fakes} (src/test).
 */
public interface LedgerPort {

    /**
     * Posts a balanced journal entry with the given legs, idempotent on
     * {@code idempotencyKey} (duplicate posts return the original entry id
     * without new postings — matching the Go ledger's
     * {@code (source, transaction_key)} uniqueness).
     *
     * @param idempotencyKey ledger-side transaction key, e.g.
     *                       {@code fx:cnv_...} (globally unique per
     *                       conversion, independent from the client's
     *                       Idempotency-Key)
     * @param legs           ordered journal legs; must balance per currency
     * @return the ledger journal entry id (UUID)
     */
    String postTransaction(String idempotencyKey, List<Leg> legs);

    /** Statement (posting lines) of one account, e.g. {@code fx-position:USD}. */
    LedgerStatement getStatement(String accountRef);
}
