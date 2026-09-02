/**
 * Amount parsing from keypad input — string-only, no floats anywhere.
 *
 * The Send/Payouts keypads build a decimal string ("12.50"). This module
 * converts that string into exact bigint minor units by string splitting and
 * integer validation: `parseFloat` is never used (ADR 001 §4 — an amount
 * parsed through a float can silently round at the 2^53 boundary and at the
 * 17th significant digit).
 */

import type { Currency } from '../api/types';
import { currencyExponent } from './format';

/** A parsed, user-entered amount: exact minor units + display currency. */
export interface ParsedAmount {
  amountMinor: bigint;
  currency: Currency;
  exponent: number;
}

export class AmountParseError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'AmountParseError';
  }
}

const DIGITS_ONLY = /^\d+$/;

/**
 * Parses a keypad decimal string ("12.5", "0.05", "1500") into exact minor
 * units for a currency exponent. Rules:
 * - optional leading zeros are normalised ("007" → 7n)
 * - at most `exponent` fractional digits ("12.567" @ exp 2 is rejected —
 *   silently dropping a digit would invent money)
 * - a bare "." is invalid; "" is invalid (callers show their own empty state)
 * - negative signs are rejected (keypads are positive-only by construction)
 */
export function parseAmountToMinor(input: string, exponent: number): bigint {
  if (typeof input !== 'string' || input.length === 0) {
    throw new AmountParseError('amount is required');
  }
  if (!Number.isInteger(exponent) || exponent < 0 || exponent > 18) {
    throw new AmountParseError(`exponent must be an integer between 0 and 18 (got ${exponent})`);
  }
  if (input.startsWith('-') || input.startsWith('+')) {
    throw new AmountParseError('amount must be positive');
  }

  const dotIndex = input.indexOf('.');
  const hasDot = dotIndex !== -1;
  if (hasDot && input.indexOf('.', dotIndex + 1) !== -1) {
    throw new AmountParseError('amount has more than one decimal point');
  }

  const wholePart = hasDot ? input.slice(0, dotIndex) : input;
  const fractionPart = hasDot ? input.slice(dotIndex + 1) : '';

  if (hasDot && fractionPart.length === 0) {
    throw new AmountParseError('amount ends with a decimal point');
  }
  if (wholePart.length === 0) {
    // ".50" style input — treat the whole part as 0 (keypads emit "0.50").
    if (fractionPart.length === 0) {
      throw new AmountParseError('amount is empty');
    }
  } else if (!DIGITS_ONLY.test(wholePart)) {
    throw new AmountParseError(`amount whole part must be digits (got ${JSON.stringify(wholePart)})`);
  }
  if (fractionPart.length > 0 && !DIGITS_ONLY.test(fractionPart)) {
    throw new AmountParseError(
      `amount fraction must be digits (got ${JSON.stringify(fractionPart)})`,
    );
  }
  if (fractionPart.length > exponent) {
    throw new AmountParseError(
      `amount has ${fractionPart.length} fractional digits but the currency supports at most ${exponent}`,
    );
  }

  const whole = wholePart.length === 0 ? 0n : BigInt(wholePart);
  const scale = 10n ** BigInt(exponent);
  const fraction =
    fractionPart.length === 0
      ? 0n
      : BigInt(fractionPart) * 10n ** BigInt(exponent - fractionPart.length);
  const amountMinor = whole * scale + fraction;
  if (amountMinor <= 0n) {
    throw new AmountParseError('amount must be greater than zero');
  }
  return amountMinor;
}

/** Parses with a currency's exponent (see {@link parseAmountToMinor}). */
export function parseAmount(input: string, currency: Currency): ParsedAmount {
  const exponent = currencyExponent(currency);
  return {
    amountMinor: parseAmountToMinor(input, exponent),
    currency,
    exponent,
  };
}

/**
 * Renders a bigint minor-unit amount back to the keypad string form
 * ("1250" @ exp 2 → "12.50"). Inverse of {@link parseAmountToMinor} for
 * values that fit; used for keypad editing.
 */
export function minorToAmountString(amountMinor: bigint, exponent: number): string {
  if (amountMinor < 0n) {
    throw new AmountParseError('amount must be non-negative');
  }
  const scale = 10n ** BigInt(exponent);
  const major = amountMinor / scale;
  const minor = amountMinor % scale;
  if (exponent === 0) {
    return major.toString();
  }
  return `${major.toString()}.${minor.toString().padStart(exponent, '0')}`;
}
