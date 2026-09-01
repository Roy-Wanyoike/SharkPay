package com.sharkpay.fx.ports;

import com.sharkpay.fx.domain.Rate;

/**
 * Port to an external rate source (HoneyCoin indicative feed + backup source
 * per docs/ARCHITECTURE.md &#167;6). The current implementation is the fake
 * implementation is the fail-fast integration-pending placeholder in
 * {@code com.sharkpay.fx.config} until the REST adapter to the providers
 * service lands at integration; local tests use the in-tree fake in
 * {@code com.sharkpay.fx.fakes} (src/test).
 */
public interface RateProvider {

    /**
     * Raw market rate for the base&#8594;quote pair, as an exact rational of
     * quote-currency minor units per base-currency minor unit (see
     * {@link Rate} semantics).
     *
     * @throws com.sharkpay.fx.domain.UnsupportedCurrencyPairException if no
     *         source serves the pair
     */
    Rate rawRate(String baseCurrency, String quoteCurrency);
}
