/** Money helpers: safe parsing, lossless bigint parsing, float-free formatting. */

import { describe, expect, it } from 'vitest';
import {
  MAX_SAFE_AMOUNT_MINOR,
  MoneyParseError,
  formatMoney,
  isMoney,
  parseMoney,
  parseMoneyLossless,
} from '../src/money.js';

describe('isMoney', () => {
  it('accepts contract-shaped money', () => {
    expect(isMoney({ amount_minor: 1500, currency: 'KES', exponent: 2 })).toBe(true);
    expect(isMoney({ amount_minor: -1500, currency: 'USD', exponent: 2 })).toBe(true);
    expect(isMoney({ amount_minor: 25000000, currency: 'USDC', exponent: 6 })).toBe(true);
  });

  it('rejects malformed values', () => {
    expect(isMoney(null)).toBe(false);
    expect(isMoney('1500')).toBe(false);
    expect(isMoney([])).toBe(false);
    expect(isMoney({ amount_minor: 1.5, currency: 'KES', exponent: 2 })).toBe(false); // float
    expect(isMoney({ amount_minor: '1500', currency: 'KES', exponent: 2 })).toBe(false);
    expect(isMoney({ amount_minor: 1500, currency: 'CHF', exponent: 2 })).toBe(false); // not a v1 currency
    expect(isMoney({ amount_minor: 1500, currency: 'KES', exponent: 19 })).toBe(false);
    expect(isMoney({ amount_minor: 1500, currency: 'KES', exponent: -1 })).toBe(false);
    expect(isMoney({ amount_minor: 1500, currency: 'KES', exponent: 2.5 })).toBe(false);
  });

  it('does not enforce the 2^53 safe range (parseMoney does)', () => {
    expect(isMoney({ amount_minor: MAX_SAFE_AMOUNT_MINOR + 1, currency: 'KES', exponent: 2 })).toBe(true);
  });
});

describe('parseMoney', () => {
  it('parses valid money and narrows the type', () => {
    const money = parseMoney({ amount_minor: 150000, currency: 'KES', exponent: 2 });
    expect(money).toEqual({ amount_minor: 150000, currency: 'KES', exponent: 2 });
  });

  it('throws MoneyParseError with field-specific messages', () => {
    expect(() => parseMoney('nope')).toThrow(MoneyParseError);
    expect(() => parseMoney(null)).toThrow(/money must be a JSON object/);
    expect(() => parseMoney({ currency: 'KES', exponent: 2 })).toThrow(/amount_minor/);
    expect(() => parseMoney({ amount_minor: 1.5, currency: 'KES', exponent: 2 })).toThrow(/amount_minor/);
    expect(() => parseMoney({ amount_minor: 10, currency: 'CHF', exponent: 2 })).toThrow(/currency/);
    expect(() => parseMoney({ amount_minor: 10, currency: 'KES', exponent: 21 })).toThrow(/exponent/);
  });

  it('refuses amounts beyond the JSON-safe integer range instead of silently rounding', () => {
    expect(() => parseMoney({ amount_minor: MAX_SAFE_AMOUNT_MINOR + 1, currency: 'KES', exponent: 2 })).toThrow(
      /parseMoneyLossless/,
    );
    expect(() => parseMoney({ amount_minor: -MAX_SAFE_AMOUNT_MINOR - 1, currency: 'KES', exponent: 2 })).toThrow(
      MoneyParseError,
    );
    // The boundary itself is fine.
    expect(parseMoney({ amount_minor: MAX_SAFE_AMOUNT_MINOR, currency: 'KES', exponent: 2 }).amount_minor).toBe(
      MAX_SAFE_AMOUNT_MINOR,
    );
  });
});

describe('parseMoneyLossless', () => {
  it('keeps int64 amounts beyond 2^53 exact as bigint', () => {
    const raw = '{"amount_minor":9007199254740993,"currency":"KES","exponent":2}';
    const money = parseMoneyLossless(raw);
    expect(money.amount_minor).toBe(9007199254740993n);
    expect(money.currency).toBe('KES');
    expect(money.exponent).toBe(2);
    // Demonstrating the hazard the helper exists for: plain JSON.parse loses the unit digit.
    expect(JSON.parse(raw).amount_minor).toBe(9007199254740992);
  });

  it('handles negative amounts and whitespace around the literal', () => {
    const money = parseMoneyLossless('{ "amount_minor" :  -12345 , "currency": "USD", "exponent": 2 }');
    expect(money.amount_minor).toBe(-12345n);
    expect(money.currency).toBe('USD');
  });

  it('throws MoneyParseError on invalid raw input', () => {
    expect(() => parseMoneyLossless('not json')).toThrow(MoneyParseError);
    expect(() => parseMoneyLossless('[]')).toThrow(/JSON object/);
    expect(() => parseMoneyLossless('{"currency":"KES","exponent":2}')).toThrow(/amount_minor/);
    expect(() => parseMoneyLossless('{"amount_minor":12.5,"currency":"KES","exponent":2}')).toThrow(MoneyParseError);
    expect(() => parseMoneyLossless('{"amount_minor":10,"currency":"CHF","exponent":2}')).toThrow(/currency/);
    expect(() => parseMoneyLossless('{"amount_minor":10,"currency":"KES","exponent":99}')).toThrow(/exponent/);
  });
});

describe('formatMoney (float-free string arithmetic)', () => {
  it('renders exponent-2 fiat', () => {
    expect(formatMoney({ amount_minor: 1500, currency: 'KES', exponent: 2 })).toBe('15.00 KES');
    expect(formatMoney({ amount_minor: 0, currency: 'USD', exponent: 2 })).toBe('0.00 USD');
    expect(formatMoney({ amount_minor: 5, currency: 'USD', exponent: 2 })).toBe('0.05 USD');
    expect(formatMoney({ amount_minor: -1500, currency: 'GBP', exponent: 2 })).toBe('-15.00 GBP');
  });

  it('renders exponent-6 stablecoins', () => {
    expect(formatMoney({ amount_minor: 25000000, currency: 'USDC', exponent: 6 })).toBe('25.000000 USDC');
    expect(formatMoney({ amount_minor: 1, currency: 'USDT', exponent: 6 })).toBe('0.000001 USDT');
  });

  it('renders exponent-0 amounts without a decimal separator', () => {
    expect(formatMoney({ amount_minor: 5, currency: 'KES', exponent: 0 })).toBe('5 KES');
  });

  it('renders bigint money exactly, including beyond 2^53', () => {
    expect(formatMoney({ amount_minor: 9007199254740993n, currency: 'KES', exponent: 2 })).toBe(
      '90071992547409.93 KES',
    );
  });

  it('refuses unsafe number amounts and bad exponents', () => {
    expect(() => formatMoney({ amount_minor: MAX_SAFE_AMOUNT_MINOR + 1, currency: 'KES', exponent: 2 })).toThrow(
      /BigIntMoney/,
    );
    expect(() => formatMoney({ amount_minor: 5, currency: 'KES', exponent: 2.5 })).toThrow(/exponent/);
  });
});
