package com.sharkpay.gateway.config;

import com.sharkpay.gateway.ports.UpstreamPort;

import java.util.UUID;

/**
 * Fail-fast placeholder {@link UpstreamPort} adapter: the real JDK
 * HttpClient routing adapter (route table → internal service URLs, e.g.
 * {@code API_GATEWAY_UPSTREAM_PAYMENTS}) lands at integration time,
 * centrally, per ADR 003 §3. Refusing loudly per call keeps the public
 * surface honest: nothing is proxied into a half-wired mesh, and the
 * caller sees a 503-style failure instead of a silent success.
 */
public final class IntegrationPendingUpstream implements UpstreamPort {

    @Override
    public UpstreamResponse forward(UpstreamRequest request) {
        throw new IllegalStateException("UpstreamPort adapter is not wired yet: the real HTTP"
                + " routing client (JDK HttpClient against the internal service URLs) lands at"
                + " integration time (ADR 003). Cannot forward " + request.method() + " "
                + request.path() + " for principal " + request.principalId() + ".");
    }
}
