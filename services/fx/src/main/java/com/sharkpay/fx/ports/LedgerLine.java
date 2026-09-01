package com.sharkpay.fx.ports;

import com.sharkpay.fx.domain.Direction;
import com.sharkpay.fx.domain.Leg;

import java.util.List;

/**
 * One line of an account statement returned by {@link LedgerPort#getStatement}.
 *
 * @param transactionKey the ledger transaction key that produced the line
 *                       (e.g. {@code fx:cnv_...})
 * @param direction      posting direction on the account
 * @param amountMinor    posted amount in the account's currency minor units
 */
public record LedgerLine(String transactionKey, Direction direction, long amountMinor) {
}
