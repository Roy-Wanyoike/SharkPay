package com.sharkpay.fx.config;

import com.sharkpay.fx.domain.Rate;
import com.sharkpay.fx.ports.RateProvider;

/**
 * Fail-fast placeholder {@link RateProvider} adapter: quoting requires an
 * external rate source (HoneyCoin indicative + backup per
 * docs/ARCHITECTURE.md §6), whose REST adapter against the providers
 * service is wired at integration time by the integrator (ADR 003 §3 —
 * REST clients land once, centrally).
 *
 * <p>Refusing loudly per call (instead of silently quoting fake rates)
 * keeps the money path honest: no quote can be issued against a rate no
 * source actually published.</p>
 */
public final class IntegrationPendingRateProvider implements RateProvider {

    @Override
    public Rate rawRate(String baseCurrency, String quoteCurrency) {
        throw new IllegalStateException("RateProvider adapter is not wired yet: the REST rate"
                + " source (providers service) lands at integration time (ADR 003)."
                + " Cannot quote the pair " + baseCurrency + "/" + quoteCurrency + ".");
    }
}
