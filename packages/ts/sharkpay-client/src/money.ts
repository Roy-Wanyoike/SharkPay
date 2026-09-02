/**
 * Money helpers: safe runtime validation and exact (bigint) parsing for the
 * integer-only money shape of contracts/openapi/v1/common.yaml.
 *
 * ## Why this exists
 *
 * `Money.amount_minor` is `int64` on the wire. The client (and any consumer
 * using `JSON.parse`) reads it as a JS `number`, which is exact only within
 * ±(2^53 − 1). For every realistic v1 balance that is fine, but:
 *
 * - `parseMoney` *asserts* the safe range and throws `MoneyParseError`
 *   otherwise, so an out-of-envelope value can never silently round.
 * - `parseMoneyLossless` parses the raw JSON *text* with `amount_minor`
 *   stringified before `JSON.parse`, then converts it to `bigint` — exact
 *   for the full int64 range.
 * - `formatMoney` renders major-unit strings with pure string arithmetic
 *   (no floats anywhere in this module).
 */

import { CURRENCIES, type Currency, type Money } from './types/common.js';

/** `Money` with `amount_minor` as an exact `bigint`. */
export interface BigIntMoney {
  amount_minor: bigint;
  currency: Currency;
  exponent: number;
}

/** Thrown when a value cannot be interpreted as contract-shaped money. */
export class MoneyParseError extends Error {
  constructor(message: string, options?: { cause?: unknown }) {
    super(message, options);
    this.name = 'MoneyParseError';
  }
}

/** Largest `amount_minor` exactly representable as a JS number: 2^53 − 1. */
export const MAX_SAFE_AMOUNT_MINOR = Number.MAX_SAFE_INTEGER;

const EXPONENT_MIN = 0;
const EXPONENT_MAX = 18;

function isCurrency(value: unknown): value is Currency {
  return typeof value === 'string' && (CURRENCIES as readonly string[]).includes(value);
}

function exponentIsValid(value: unknown): value is number {
  return (
    typeof value === 'number' && Number.isInteger(value) && value >= EXPONENT_MIN && value <= EXPONENT_MAX
  );
}

/**
 * Structural type guard: checks `amount_minor` is an integer, `currency` is
 * a v1 currency, and `exponent` is an integer in 0..18. Does NOT check the
 * 2^53 safety envelope (use {@link parseMoney} for that).
 */
export function isMoney(value: unknown): value is Money {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    return false;
  }
  const record = value as Record<string, unknown>;
  return (
    typeof record['amount_minor'] === 'number' &&
    Number.isInteger(record['amount_minor']) &&
    isCurrency(record['currency']) &&
    exponentIsValid(record['exponent'])
  );
}

/**
 * Parse and validate an unknown value as {@link Money}, refusing amounts
 * beyond the JSON-safe integer range (±{@link MAX_SAFE_AMOUNT_MINOR}).
 * Throws {@link MoneyParseError} with a field-specific message.
 */
export function parseMoney(value: unknown): Money {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new MoneyParseError('money must be a JSON object');
  }
  const record = value as Record<string, unknown>;
  const amount = record['amount_minor'];
  if (typeof amount !== 'number' || !Number.isInteger(amount)) {
    throw new MoneyParseError('money.amount_minor must be an integer');
  }
  if (!Number.isSafeInteger(amount)) {
    throw new MoneyParseError(
      `money.amount_minor ${amount} is outside the JSON-safe integer range ±${MAX_SAFE_AMOUNT_MINOR}; ` +
        'parse the raw response text with parseMoneyLossless() to get an exact bigint',
    );
  }
  const currency = record['currency'];
  if (!isCurrency(currency)) {
    throw new MoneyParseError(
      `money.currency must be one of ${CURRENCIES.join(', ')} (got ${JSON.stringify(currency)})`,
    );
  }
  if (!exponentIsValid(record['exponent'])) {
    throw new MoneyParseError(
      `money.exponent must be an integer between ${EXPONENT_MIN} and ${EXPONENT_MAX}`,
    );
  }
  return { amount_minor: amount, currency, exponent: record['exponent'] };
}

/**
 * Losslessly parse a raw JSON money object (the exact response text) with
 * `amount_minor` as `bigint`. The trick: `amount_minor` numeric literals are
 * stringified in the raw text *before* `JSON.parse`, so no precision is
 * lost at any int64 magnitude.
 *
 * ```ts
 * const raw = await response.text(); // '{"amount_minor":9007199254740993,...}'
 * const money = parseMoneyLossless(raw); // { amount_minor: 9007199254740993n, ... }
 * ```
 */
export function parseMoneyLossless(raw: string): BigIntMoney {
  let parsed: unknown;
  try {
    // Stringify the amount_minor literal in the raw text before JSON.parse so
    // no precision is lost, e.g. `"amount_minor":9007199254740993` →
    // `"amount_minor":"9007199254740993"`.
    parsed = JSON.parse(raw.replace(/("amount_minor"\s*:\s*)(-?\d+)/g, '$1"$2"'));
  } catch (cause) {
    throw new MoneyParseError('raw value is not valid JSON', { cause });
  }
  if (typeof parsed !== 'object' || parsed === null || Array.isArray(parsed)) {
    throw new MoneyParseError('raw value must be a JSON object');
  }
  const record = parsed as Record<string, unknown>;
  const amountText = record['amount_minor'];
  if (typeof amountText !== 'string' || !/^-?\d+$/.test(amountText)) {
    throw new MoneyParseError('raw money must contain an integer amount_minor');
  }
  const currency = record['currency'];
  if (!isCurrency(currency)) {
    throw new MoneyParseError(
      `money.currency must be one of ${CURRENCIES.join(', ')} (got ${JSON.stringify(currency)})`,
    );
  }
  if (!exponentIsValid(record['exponent'])) {
    throw new MoneyParseError(
      `money.exponent must be an integer between ${EXPONENT_MIN} and ${EXPONENT_MAX}`,
    );
  }
  return { amount_minor: BigInt(amountText), currency, exponent: record['exponent'] };
}

/**
 * Render money as a major-unit string using pure string arithmetic — no
 * floats. Examples: `{1500, KES, 2}` → `'15.00 KES'`;
 * `{5, USD, 2}` → `'0.05 USD'`; `{25000000, USDC, 6}` → `'25.000000 USDC'`;
 * `{5, JPY-like, 0}` → `'5 KES'` (no fractional digits).
 *
 * Throws {@link MoneyParseError} if a `number` amount is outside the
 * JSON-safe integer range (convert to `BigIntMoney` first).
 */
export function formatMoney(money: Money | BigIntMoney): string {
  if (typeof money.amount_minor === 'number') {
    if (!Number.isSafeInteger(money.amount_minor)) {
      throw new MoneyParseError(
        `amount_minor ${money.amount_minor} is outside the JSON-safe integer range; ` +
          'use a BigIntMoney from parseMoneyLossless()',
      );
    }
    if (!exponentIsValid(money.exponent)) {
      throw new MoneyParseError(`money.exponent must be an integer between 0 and 18`);
    }
  }
  const amount =
    typeof money.amount_minor === 'bigint' ? money.amount_minor : BigInt(money.amount_minor);
  const negative = amount < 0n;
  const digits = (negative ? -amount : amount).toString();
  const exponent = money.exponent;

  let whole: string;
  let fraction: string;
  if (exponent === 0) {
    whole = digits;
    fraction = '';
  } else if (digits.length <= exponent) {
    whole = '0';
    fraction = '0'.repeat(exponent - digits.length) + digits;
  } else {
    whole = digits.slice(0, digits.length - exponent);
    fraction = digits.slice(digits.length - exponent);
  }
  return `${negative ? '-' : ''}${whole}${fraction.length > 0 ? `.${fraction}` : ''} ${money.currency}`;
}
