package com.sharkpay.risk.domain;

/**
 * Money-movement channel the evaluated transaction travels on. Superset of
 * the event contract's {@code transaction_type} vocabulary: when a caller
 * does not give an explicit transaction type, the channel decides the
 * default used in {@code risk.decision.v1} payloads (documented mapping:
 * wallet -> transfer, fx -> payment).
 */
public enum Channel implements WireValue {

    WALLET("wallet", TransactionType.TRANSFER),
    PAYMENT("payment", TransactionType.PAYMENT),
    PAYOUT("payout", TransactionType.PAYOUT),
    TRANSFER("transfer", TransactionType.TRANSFER),
    FX("fx", TransactionType.PAYMENT);

    private final String wire;
    private final TransactionType defaultTransactionType;

    Channel(String wire, TransactionType defaultTransactionType) {
        this.wire = wire;
        this.defaultTransactionType = defaultTransactionType;
    }

    public String wire() {
        return wire;
    }

    /** Transaction type reported on risk events when the caller omits one. */
    public TransactionType defaultTransactionType() {
        return defaultTransactionType;
    }

    @Override
    public String toString() {
        return wire;
    }

    public static java.util.Optional<Channel> fromWire(String wire) {
        for (Channel channel : values()) {
            if (channel.wire.equals(wire)) {
                return java.util.Optional.of(channel);
            }
        }
        return java.util.Optional.empty();
    }
}
