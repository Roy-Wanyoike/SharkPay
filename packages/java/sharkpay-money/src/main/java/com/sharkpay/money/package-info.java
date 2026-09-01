/**
 * SharkPay money library (Java port of packages/go/money).
 *
 * <p>Integer-only money value type: minor units + currency + exponent.
 * Floating-point values are forbidden anywhere in money code. All money
 * arithmetic in Java services must go through {@link com.sharkpay.money.Money};
 * raw {@code double}/{@code float} money values must never appear in domain,
 * service, storage, or API layers.
 *
 * <p>At the API boundary, render money as the canonical JSON object
 * {@code {"amount_minor": <long>, "currency": "<CODE>", "exponent": <int>}}
 * via a per-service DTO mapper (the library deliberately carries no JSON
 * dependency).
 */
package com.sharkpay.money;
