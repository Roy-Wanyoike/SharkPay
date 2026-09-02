package com.sharkpay.gateway.api.routing;

import com.sharkpay.gateway.domain.Scope;

import java.util.Optional;

/**
 * Route classes of the gateway surface. Each class maps to exactly one
 * required scope per HTTP method — read verbs need {@code :read}, mutating
 * verbs {@code :write} (docs/API-CONTRACTS.md §5 scope catalog;
 * {@code transfers} is write-only, {@code wallets} is read-only in V1, so a
 * mutating wallets call has NO satisfiable scope and is rejected —
 * fail-closed). {@code UNKNOWN} is the fail-closed default: any path the
 * table does not know is a 403, never an accidental open route.
 */
public enum RouteClass {

    PAYMENTS {
        @Override
        public Optional<Scope> requiredScope(String httpMethod) {
            return "GET".equalsIgnoreCase(httpMethod)
                    ? Optional.of(Scope.PAYMENTS_READ) : Optional.of(Scope.PAYMENTS_WRITE);
        }
    },
    PAYOUTS {
        @Override
        public Optional<Scope> requiredScope(String httpMethod) {
            return "GET".equalsIgnoreCase(httpMethod)
                    ? Optional.of(Scope.PAYOUTS_READ) : Optional.of(Scope.PAYOUTS_WRITE);
        }
    },
    TRANSFERS {
        @Override
        public Optional<Scope> requiredScope(String httpMethod) {
            return Optional.of(Scope.TRANSFERS_WRITE);
        }
    },
    WALLETS {
        @Override
        public Optional<Scope> requiredScope(String httpMethod) {
            return "GET".equalsIgnoreCase(httpMethod)
                    ? Optional.of(Scope.WALLETS_READ) : Optional.empty();
        }
    },
    FX {
        @Override
        public Optional<Scope> requiredScope(String httpMethod) {
            return "GET".equalsIgnoreCase(httpMethod)
                    ? Optional.of(Scope.FX_READ) : Optional.of(Scope.FX_WRITE);
        }
    },
    WEBHOOKS {
        @Override
        public Optional<Scope> requiredScope(String httpMethod) {
            return Optional.of(Scope.WEBHOOKS_MANAGE);
        }
    },
    API_KEYS {
        @Override
        public Optional<Scope> requiredScope(String httpMethod) {
            return Optional.of(Scope.API_KEYS_MANAGE);
        }
    },
    SANDBOX {
        @Override
        public Optional<Scope> requiredScope(String httpMethod) {
            return "GET".equalsIgnoreCase(httpMethod)
                    ? Optional.of(Scope.PAYMENTS_READ) : Optional.of(Scope.PAYMENTS_WRITE);
        }
    },
    /** Fail-closed default: no route, no scope, always 403. */
    UNKNOWN {
        @Override
        public Optional<Scope> requiredScope(String httpMethod) {
            return Optional.empty();
        }
    };

    /** The scope a request with this method must hold (empty → 403). */
    public abstract Optional<Scope> requiredScope(String httpMethod);
}
