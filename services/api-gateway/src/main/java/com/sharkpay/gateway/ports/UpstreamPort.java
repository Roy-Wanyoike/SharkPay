package com.sharkpay.gateway.ports;

import java.util.UUID;

/**
 * Upstream port: forwards an authenticated /v1 request to the owning
 * internal service (payments, payouts, transfers, wallets, fx). This is the
 * passthrough skeleton seam — the production adapter (JDK HttpClient
 * against the internal service URLs from the route table) lands at
 * integration time per ADR 003 §3; until then the wiring fails fast.
 *
 * <p>Principal propagation: the authenticated caller's principal id travels
 * with the request so upstream services can enforce per-principal
 * ownership; the API key itself never crosses the boundary.</p>
 */
public interface UpstreamPort {

    UpstreamResponse forward(UpstreamRequest request);

    /** An authenticated request to forward. */
    record UpstreamRequest(String method, String path, String body, UUID principalId) {
    }

    /** The upstream's response, relayed verbatim to the caller. */
    record UpstreamResponse(int status, String body) {

        public UpstreamResponse {
            if (status < 100 || status > 599) {
                throw new IllegalArgumentException("status must be a valid HTTP code");
            }
        }
    }
}
