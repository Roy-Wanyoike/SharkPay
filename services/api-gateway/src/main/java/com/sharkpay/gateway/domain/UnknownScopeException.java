package com.sharkpay.gateway.domain;

/** A scope name outside the fail-closed catalog (docs/API-CONTRACTS.md §5). */
public final class UnknownScopeException extends GatewayDomainException {

    private final String offendingScope;

    public UnknownScopeException(String wireName) {
        super("unknown scope: " + wireName);
        this.offendingScope = wireName;
    }

    public String offendingScope() {
        return offendingScope;
    }
}
