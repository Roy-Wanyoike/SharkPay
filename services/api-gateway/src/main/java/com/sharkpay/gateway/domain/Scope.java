package com.sharkpay.gateway.domain;

import java.util.Set;

/**
 * API key scope catalog (docs/API-CONTRACTS.md §5). Fixed and fail-closed:
 * a key can never hold a scope outside this enum, and every route class
 * demands a concrete scope — there is no implicit "any" access.
 *
 * <p>{@code API_KEYS_MANAGE} is a gateway-owned extension (the V1 catalog
 * has no key-administration scope; see the service README — flagged for the
 * integrator as an append-only contract addition).</p>
 */
public enum Scope {

    PAYMENTS_READ("payments:read"),
    PAYMENTS_WRITE("payments:write"),
    PAYOUTS_READ("payouts:read"),
    PAYOUTS_WRITE("payouts:write"),
    TRANSFERS_WRITE("transfers:write"),
    WALLETS_READ("wallets:read"),
    FX_READ("fx:read"),
    FX_WRITE("fx:write"),
    WEBHOOKS_MANAGE("webhooks:manage"),
    OPS_READ("ops:read"),
    /** Gateway extension — administering API keys (create/rotate/revoke). */
    API_KEYS_MANAGE("apikeys:manage");

    private final String wireName;

    Scope(String wireName) {
        this.wireName = wireName;
    }

    /** The wire representation used in request/response bodies and storage. */
    public String wireName() {
        return wireName;
    }

    /**
     * Parses a wire scope name; unknown names are rejected (fail-closed —
     * a typo like {@code payment:write} can never silently grant access).
     *
     * @throws UnknownScopeException when the name is not in the catalog
     */
    public static Scope parse(String wireName) {
        for (Scope scope : values()) {
            if (scope.wireName.equals(wireName)) {
                return scope;
            }
        }
        throw new UnknownScopeException(wireName);
    }

    /** Renders a scope set to wire names (stable order, mapping helper). */
    public static Set<String> toWireNames(Set<Scope> scopes) {
        Set<String> names = new java.util.LinkedHashSet<>();
        for (Scope scope : scopes) {
            names.add(scope.wireName());
        }
        return java.util.Collections.unmodifiableSet(names);
    }

    /** Parses a set of wire names (order preserved, unknown names rejected). */
    public static Set<Scope> parseAll(java.util.List<String> wireNames) {
        Set<Scope> scopes = new java.util.LinkedHashSet<>();
        for (String name : wireNames) {
            scopes.add(parse(name));
        }
        return java.util.Collections.unmodifiableSet(scopes);
    }
}
