package com.sharkpay.wallet.domain;

import com.sharkpay.money.Money;

/**
 * A projection line with its running balance: what the wallet statement API
 * returns ({@code balance_after} = wallet balance after this entry, in
 * ledger posting order — see wallets.yaml StatementEntry).
 */
public record StatementLine(ProjectionLeg leg, Money balanceAfter) {
}
