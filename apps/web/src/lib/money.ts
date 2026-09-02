import type { Currency, Money, Rate } from "@/lib/api/sdk/types";

/**
 * Integer-exact money formatting (ADR 001 §4: floating-point money is
 * forbidden). All arithmetic happens on `bigint` so int64 minor units are
 * never rounded, and grouping is done with string operations.
 */

export type { Currency, Money, Rate };

export const CURRENCY_EXPONENTS: Readonly<Record<Currency, number>> = {
  KES: 2,
  USD: 2,
  EUR: 2,
  GBP: 2,
  USDC: 6,
  USDT: 6,
};

export function currencyExponent(currency: Currency): number {
  return CURRENCY_EXPONENTS[currency] ?? 2;
}

function groupDigits(digits: string): string {
  return digits.replace(/\B(?=(\d{3})+(?!\d))/g, ",");
}

/**
 * Formats a minor-unit integer exactly, e.g. 150000 @ exp 2 → "1,500.00".
 * Accepts bigint for float-unsafe amounts: a JS number literal like
 * 9007199254740993 is ALREADY corrupted to ...992 at parse time — JSON money
 * above 2^53 must travel as bigint (see the SDK's money-safety notes).
 */
export function formatMinor(
  amountMinor: number | bigint,
  exponent: number,
  options: { withSign?: boolean } = {},
): string {
  const value =
    typeof amountMinor === "bigint" ? amountMinor : BigInt(Math.trunc(amountMinor));
  const negative = value < 0n;
  const absolute = negative ? -value : value;
  const scale = 10n ** BigInt(exponent);
  const major = absolute / scale;
  const minor = absolute % scale;
  const fraction =
    exponent > 0 ? `.${minor.toString().padStart(exponent, "0")}` : "";
  const sign = negative ? "-" : options.withSign ? "+" : "";
  return `${sign}${groupDigits(major.toString())}${fraction}`;
}

/** Formats a contract `Money` object, e.g. "KES 1,500.00". */
export function formatMoney(money: Money): string {
  return `${money.currency} ${formatMinor(money.amount_minor, money.exponent)}`;
}

/** Formats a contract `Rate`, e.g. value_minor 7719 @ exp 4 → "0.7719". */
export function formatRate(rate: Rate): string {
  const scale = 10n ** BigInt(rate.exponent);
  const whole = BigInt(rate.value_minor) / scale;
  const fraction = BigInt(rate.value_minor) % scale;
  const fractionText =
    rate.exponent > 0
      ? `.${fraction.toString().padStart(rate.exponent, "0")}`
      : "";
  return `${whole.toString()}${fractionText}`;
}
