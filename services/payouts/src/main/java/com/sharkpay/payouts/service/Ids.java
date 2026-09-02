package com.sharkpay.payouts.service;

import java.util.UUID;

/**
 * Public id generation for payouts and transfers. Ids match the contract
 * patterns {@code ^pot_[0-9A-Za-z]{20,}$} / {@code ^trf_[0-9A-Za-z]{20,}$}
 * (UUID hex, 32 chars, prefixed), the paired UUID is the ledger
 * {@code source_ref}, and error-envelope request ids are
 * {@code ^req_[0-9A-Za-z]+$}.
 */
public final class Ids {

    private Ids() {
    }

    /** Creates a new transfer identity: public id + ledger source ref. */
    public static Identity newTransferId() {
        UUID ref = UUID.randomUUID();
        return new Identity("trf_" + hex(ref), ref);
    }

    /** Creates a new payout identity: public id + ledger source ref. */
    public static Identity newPayoutId() {
        UUID ref = UUID.randomUUID();
        return new Identity("pot_" + hex(ref), ref);
    }

    public static String requestId() {
        return "req_" + hex(UUID.randomUUID());
    }

    private static String hex(UUID ref) {
        return ref.toString().replace("-", "");
    }

    /** A public id and the UUID it was minted from (ledger source_ref). */
    public record Identity(String publicId, UUID internalRef) {
    }
}
