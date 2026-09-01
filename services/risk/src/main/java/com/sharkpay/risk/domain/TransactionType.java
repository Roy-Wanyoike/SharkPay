package com.sharkpay.risk.domain;

/**
 * Transaction kind reported on {@code risk.decision.v1} events
 * ({@code payment | payout | transfer}) — the contract vocabulary of the
 * money-movement services that call the risk engine.
 */
public enum TransactionType implements WireValue {

    PAYMENT("payment"),
    PAYOUT("payout"),
    TRANSFER("transfer");

    private final String wire;

    TransactionType(String wire) {
        this.wire = wire;
    }

    public String wire() {
        return wire;
    }

    @Override
    public String toString() {
        return wire;
    }

    public static java.util.Optional<TransactionType> fromWire(String wire) {
        for (TransactionType type : values()) {
            if (type.wire.equals(wire)) {
                return java.util.Optional.of(type);
            }
        }
        return java.util.Optional.empty();
    }
}
