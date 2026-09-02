/**
 * Integer-exact money formatting (ADR 001 §4: floating-point money is
 * forbidden). Mirrors apps/web/src/lib/money.ts (`formatMinor`) so the mobile
 * and web consoles render identical strings for identical minor units.
 *
 * All arithmetic happens on `bigint` so int64 minor units are never rounded,
 * and grouping is done with string operations. `parseFloat`/`Number` never
 * touch an amount anywhere in this module (see money-safety notes in
 * src/api/types.ts).
 */

import type { BigIntMoney, Currency, Money } from '../api/types';

/** Per-currency minor-unit exponents (common.yaml / PRD §7 D2). */
export const CURRENCY_EXPONENTS: Readonly<Record<Currency, number>> = {
  KES: 2,
  USD: 2,
  EUR: 2,
  GBP: 2,
  USDC: 6,
  USDT: 6,
};

/** Minor-unit exponent for a currency (2 for V1 fiat, 6 for stablecoins). */
export function currencyExponent(currency: Currency): number {
  return CURRENCY_EXPONENTS[currency] ?? 2;
}

function groupDigits(digits: string): string {
  return digits.replace(/\B(?=(\d{3})+(?!\d))/g, ',');
}

/**
 * Formats a minor-unit integer exactly, e.g. 150000 @ exp 2 → "1,500.00".
 *
 * Accepts `bigint` for float-unsafe amounts: a JS number literal like
 * 9007199254740993 is ALREADY corrupted to …992 at parse time — JSON money
 * above 2^53 must travel as `bigint` (see the SDK's money-safety notes).
 * `number` inputs must be SAFE integers; anything else throws rather than
 * silently rounding — a display path must never invent digits.
 */
export function formatMinor(
  amountMinor: number | bigint,
  exponent: number,
  options: { withSign?: boolean } = {},
): string {
  if (typeof amountMinor === 'number') {
    if (!Number.isInteger(amountMinor)) {
      throw new TypeError(
        `formatMinor refuses non-integer amounts (got ${amountMinor}); convert to bigint first`,
      );
    }
    if (!Number.isSafeInteger(amountMinor)) {
      throw new RangeError(
        `formatMinor refuses float-unsafe integers (got ${amountMinor}); use a bigint`,
      );
    }
  }
  if (!Number.isInteger(exponent) || exponent < 0 || exponent > 18) {
    throw new RangeError(`exponent must be an integer between 0 and 18 (got ${exponent})`);
  }
  const value = typeof amountMinor === 'bigint' ? amountMinor : BigInt(amountMinor);
  const negative = value < 0n;
  const absolute = negative ? -value : value;
  const scale = 10n ** BigInt(exponent);
  const major = absolute / scale;
  const minor = absolute % scale;
  const fraction = exponent > 0 ? `.${minor.toString().padStart(exponent, '0')}` : '';
  const sign = negative ? '-' : options.withSign ? '+' : '';
  return `${sign}${groupDigits(major.toString())}${fraction}`;
}

/**
 * Converts a contract `Money` to its exact `BigIntMoney` form for display and
 * arithmetic. REFUSES float-unsafe `amount_minor` (the envelope note in
 * src/api/types.ts): better to fail loudly than to display a corrupted
 * balance.
 */
export function toBigIntMoney(money: Money): BigIntMoney {
  if (!Number.isSafeInteger(money.amount_minor)) {
    throw new RangeError(
      `amount_minor ${money.amount_minor} is outside the JSON-safe integer range ±${Number.MAX_SAFE_INTEGER}; ` +
        'the server sent money this client cannot represent exactly',
    );
  }
  return {
    amount_minor: BigInt(money.amount_minor),
    currency: money.currency,
    exponent: money.exponent,
  };
}

/** Formats a contract `Money` object, e.g. "KES 1,500.00". */
export function formatMoney(money: Money | BigIntMoney): string {
  return `${money.currency} ${formatMinor(money.amount_minor, money.exponent)}`;
}

/**
 * Converts an exact bigint minor-unit amount into the wire `number` form
 * (JSON int64 is exact only to ±2^53−1; beyond that the app refuses rather
 * than sending a corrupted amount).
 */
export function minorToWireNumber(amountMinor: bigint): number {
  const asNumber = Number(amountMinor);
  if (!Number.isSafeInteger(asNumber)) {
    throw new RangeError(
      `amount ${amountMinor.toString()}n is outside the JSON-safe integer range ±${Number.MAX_SAFE_INTEGER}`,
    );
  }
  return asNumber;
}

/** Sums a list of same-currency BigInt money values exactly. */
export function sumMoney(amounts: readonly BigIntMoney[]): BigIntMoney {
  if (amounts.length === 0) {
    throw new TypeError('sumMoney requires at least one amount');
  }
  const first = amounts[0];
  if (first === undefined) {
    throw new TypeError('sumMoney requires at least one amount');
  }
  let total = 0n;
  for (const amount of amounts) {
    if (amount.currency !== first.currency || amount.exponent !== first.exponent) {
      throw new TypeError(
        `sumMoney refuses mixed currencies/exponents (${first.currency}/${first.exponent} vs ${amount.currency}/${amount.exponent})`,
      );
    }
    total += amount.amount_minor;
  }
  return { amount_minor: total, currency: first.currency, exponent: first.exponent };
}
