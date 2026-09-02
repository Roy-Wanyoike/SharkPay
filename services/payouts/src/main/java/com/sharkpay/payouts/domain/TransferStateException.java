package com.sharkpay.payouts.domain;

/** A transfer transition outside docs/STATE-MACHINES.md §3 (409). */
public class TransferStateException extends PayoutsDomainException {

    private final String transferId;
    private final TransferState from;
    private final TransferState attempted;

    public TransferStateException(String transferId, TransferState from, TransferState attempted) {
        super("transfer " + transferId + " is in state " + from.wireName()
                + " and cannot transition to " + attempted.wireName());
        this.transferId = transferId;
        this.from = from;
        this.attempted = attempted;
    }

    public String transferId() {
        return transferId;
    }

    public TransferState from() {
        return from;
    }

    public TransferState attempted() {
        return attempted;
    }
}
