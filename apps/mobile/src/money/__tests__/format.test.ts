/**
 * Integer-exact money formatting — the money-safety core of the app (ADR
 * 001 §4: floats never touch amounts). 2^53+1 is the canonical corruption
 * probe: only bigint keeps it exact.
 */

import { describe, expect, it } from '@jest/globals';

import {
  currencyExponent,
  formatMinor,
  formatMoney,
  minorToWireNumber,
  toBigIntMoney,
} from '../format';

describe('formatMinor', () => {
  it('formats exponent-2 amounts with grouping and a padded fraction', () => {
    expect(formatMinor(150000, 2)).toBe('1,500.00');
    expect(formatMinor(1, 2)).toBe('0.01');
    expect(formatMinor(0, 2)).toBe('0.00');
    expect(formatMinor(123456789, 2)).toBe('1,234,567.89');
  });

  it('formats exponent-0 amounts without a fraction', () => {
    expect(formatMinor(9007199254740993n, 0)).toBe('9,007,199,254,740,993');
  });

  it('formats stablecoin exponents (6) exactly', () => {
    expect(formatMinor(123456789n, 6)).toBe('123.456789');
    expect(formatMinor(1n, 6)).toBe('0.000001');
  });

  it('renders negatives and explicit signs exactly (no float math)', () => {
    expect(formatMinor(-150000, 2)).toBe('-1,500.00');
    expect(formatMinor(150000, 2, { withSign: true })).toBe('+1,500.00');
    expect(formatMinor(-150000, 2, { withSign: true })).toBe('-1,500.00');
  });

  it('keeps float-unsafe amounts exact via bigint (2^53+1 probe)', () => {
    // A plain number literal 9007199254740993 parses as ...992 — the bigint
    // path is the only exact representation.
    expect(formatMinor(9007199254740993n, 0)).not.toBe(formatMinor(9007199254740992n, 0));
    expect(formatMinor(-9007199254740993n, 0)).toBe('-9,007,199,254,740,993');
  });

  it('refuses to invent digits: non-integer and unsafe numbers throw', () => {
    expect(() => formatMinor(1.5, 2)).toThrow(TypeError);
    expect(() => formatMinor(9007199254740993, 0)).toThrow(RangeError);
  });

  it('validates the exponent', () => {
    expect(() => formatMinor(1, -1)).toThrow(RangeError);
    expect(() => formatMinor(1, 1.5)).toThrow(RangeError);
    expect(() => formatMinor(1, 19)).toThrow(RangeError);
  });
});

describe('currencyExponent', () => {
  it('maps V1 fiat to 2 and stablecoins to 6, defaulting to 2', () => {
    expect(currencyExponent('KES')).toBe(2);
    expect(currencyExponent('USD')).toBe(2);
    expect(currencyExponent('USDC')).toBe(6);
    expect(currencyExponent('USDT')).toBe(6);
  });
});

describe('toBigIntMoney', () => {
  it('converts JSON-safe money exactly', () => {
    expect(toBigIntMoney({ amount_minor: 150000, currency: 'KES', exponent: 2 })).toEqual({
      amount_minor: 150000n,
      currency: 'KES',
      exponent: 2,
    });
  });

  it('refuses float-unsafe server money instead of displaying a corrupted balance', () => {
    expect(() => toBigIntMoney({ amount_minor: 9007199254740993, currency: 'KES', exponent: 2 })).toThrow(
      RangeError,
    );
  });
});

describe('formatMoney', () => {
  it('prefixes the currency', () => {
    expect(formatMoney({ amount_minor: 150000n, currency: 'KES', exponent: 2 })).toBe('KES 1,500.00');
    expect(formatMoney({ amount_minor: 1, currency: 'KES', exponent: 2 })).toBe('KES 0.01');
  });
});

describe('minorToWireNumber', () => {
  it('round-trips JSON-safe amounts', () => {
    expect(minorToWireNumber(150000n)).toBe(150000);
  });

  it('refuses amounts the wire cannot represent exactly', () => {
    expect(() => minorToWireNumber(9007199254740993n)).toThrow(RangeError);
    expect(() => minorToWireNumber(-9007199254740993n)).toThrow(RangeError);
  });
});
