package com.sharkpay.wallet.domain;

/**
 * An Idempotency-Key was replayed with a different request payload: the key
 * is bound to the canonical fingerprint of the request it first served, and a
 * mismatch means client misuse (409 {@code idempotency_conflict}).
 */
public class IdempotencyConflictException extends WalletDomainException {

    public IdempotencyConflictException(String idempotencyKey) {
        super("idempotency key " + idempotencyKey + " was already used with a different request");
    }
}
