package com.sharkpay.payouts.domain;

/**
 * Malformed destination or destination/rail/currency combination the rails
 * cannot serve (422 unsupported_destination — one of the codes enumerated in
 * payouts.yaml's create description).
 */
public class UnsupportedDestinationException extends PayoutsDomainException {

    public UnsupportedDestinationException(String message) {
        super(message);
    }
}
