package com.sharkpay.fx.domain;

/**
 * Posting direction of a journal leg, matching the Go ledger's double-entry
 * convention (debits = credits per currency per entry).
 */
public enum Direction {
    DEBIT,
    CREDIT
}
