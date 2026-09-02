package com.sharkpay.payouts.api.dto;

import com.sharkpay.payouts.domain.Destination;

/**
 * External payout destination JSON (contracts/openapi/v1/payouts.yaml
 * PayoutDestination — oneOf discriminated by {@code type}). Optional
 * per-type fields are omitted when null (NON_NULL inclusion).
 */
public record DestinationJson(String type, String msisdn, String bank_code, String account_number,
                              String account_name, String country, String network, String address) {

    public static DestinationJson of(Destination destination) {
        return new DestinationJson(destination.type(), destination.msisdn(), destination.bankCode(),
                destination.accountNumber(), destination.accountName(), destination.country(),
                destination.network(), destination.address());
    }

    /** Parses + validates into the domain destination. */
    public Destination toDomain() {
        return new Destination(type, msisdn, bank_code, account_number, account_name, country,
                network, address);
    }
}
