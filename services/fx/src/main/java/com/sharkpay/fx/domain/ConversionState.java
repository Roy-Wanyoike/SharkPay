package com.sharkpay.fx.domain;

/**
 * Conversion state. V1 conversion is synchronous: the 4-leg ledger entry is
 * posted before the API response, so the only state is EXECUTED. Additional
 * states may be appended additively in later versions (contract doc).
 */
public enum ConversionState {
    EXECUTED
}
