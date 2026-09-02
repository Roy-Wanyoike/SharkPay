package com.sharkpay.gateway.api.routing;

import java.util.Map;

/**
 * The route table: request path prefix → {@link RouteClass}, the single
 * place the gateway decides which surface a path belongs to. Unknown paths
 * resolve to {@link RouteClass#UNKNOWN} — the auth filter then rejects with
 * 403 (fail-closed: no path is ever reachable without a declared route
 * class and its required scope).
 */
public final class RouteTable {

    /** Internal + infrastructure prefixes never go through API-key auth. */
    public static final String INTERNAL_PREFIX = "/internal";
    public static final String ACTUATOR_PREFIX = "/actuator";

    private static final Map<String, RouteClass> PREFIXES = Map.of(
            "/v1/payments", RouteClass.PAYMENTS,
            "/v1/payouts", RouteClass.PAYOUTS,
            "/v1/transfers", RouteClass.TRANSFERS,
            "/v1/wallets", RouteClass.WALLETS,
            "/v1/fx", RouteClass.FX,
            "/v1/webhook-endpoints", RouteClass.WEBHOOKS,
            "/v1/api-keys", RouteClass.API_KEYS,
            "/sandbox", RouteClass.SANDBOX);

    private RouteTable() {
    }

    /** Resolves the route class of a request path (exact prefix or prefix + "/"). */
    public static RouteClass resolve(String path) {
        if (path == null || path.isBlank()) {
            return RouteClass.UNKNOWN;
        }
        for (Map.Entry<String, RouteClass> entry : PREFIXES.entrySet()) {
            String prefix = entry.getKey();
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return entry.getValue();
            }
        }
        return RouteClass.UNKNOWN;
    }

    /** Whether the path is outside the API-key-authenticated surface. */
    public static boolean isNonApiSurface(String path) {
        return path != null && (path.startsWith(INTERNAL_PREFIX) || path.startsWith(ACTUATOR_PREFIX));
    }
}
