/**
 * Keypad amount parsing: every branch of AmountParseError plus the
 * ".50"-style keypad input and the exponent-padded fraction math.
 */

import { describe, expect, it } from '@jest/globals';

import { minorToAmountString, parseAmount, parseAmountToMinor } from '../parse';
import { AmountParseError } from '../parse';

describe('parseAmountToMinor', () => {
  it('parses whole and fractional keypad input into exact minor units', () => {
    expect(parseAmountToMinor('1500', 2)).toBe(150000n);
    expect(parseAmountToMinor('12.5', 2)).toBe(1250n);
    expect(parseAmountToMinor('12.50', 2)).toBe(1250n);
    expect(parseAmountToMinor('1', 0)).toBe(1n);
    expect(parseAmountToMinor('0.000001', 6)).toBe(1n);
  });

  it('accepts keypad-style ".50" (empty whole part means zero)', () => {
    expect(parseAmountToMinor('.50', 2)).toBe(50n);
    expect(parseAmountToMinor('.000001', 6)).toBe(1n);
  });

  it('pads short fractions to the exponent exactly', () => {
    // "0.5" @ exponent 6 is 500000 minor units, not 5.
    expect(parseAmountToMinor('0.5', 6)).toBe(500000n);
    expect(parseAmountToMinor('1.2', 6)).toBe(1200000n);
  });

  it('rejects malformed input with distinct errors', () => {
    expect(() => parseAmountToMinor('', 2)).toThrow(AmountParseError);
    expect(() => parseAmountToMinor('-5', 2)).toThrow(/positive/);
    expect(() => parseAmountToMinor('+5', 2)).toThrow(/positive/);
    expect(() => parseAmountToMinor('1.2.3', 2)).toThrow(/more than one decimal point/);
    expect(() => parseAmountToMinor('12.', 2)).toThrow(/ends with a decimal point/);
    expect(() => parseAmountToMinor('0.001', 2)).toThrow(/fractional digits/);
  });

  it('rejects zero and non-positive results', () => {
    expect(() => parseAmountToMinor('0', 2)).toThrow(/greater than zero/);
    expect(() => parseAmountToMinor('0.00', 2)).toThrow(/greater than zero/);
  });

  it('validates the exponent', () => {
    expect(() => parseAmountToMinor('1', -1)).toThrow(AmountParseError);
    expect(() => parseAmountToMinor('1', 19)).toThrow(AmountParseError);
  });
});

describe('parseAmount', () => {
  it('resolves the exponent from the currency', () => {
    expect(parseAmount('12.50', 'KES')).toEqual({
      amountMinor: 1250n,
      currency: 'KES',
      exponent: 2,
    });
    expect(parseAmount('1.5', 'USDC')).toEqual({
      amountMinor: 1500000n,
      currency: 'USDC',
      exponent: 6,
    });
  });
});

describe('minorToAmountString', () => {
  it('round-trips the keypad form (inverse of parseAmountToMinor)', () => {
    expect(minorToAmountString(1250n, 2)).toBe('12.50');
    expect(minorToAmountString(50n, 2)).toBe('0.50');
    expect(minorToAmountString(5n, 0)).toBe('5');
    expect(minorToAmountString(500000n, 6)).toBe('0.500000');
  });

  it('rejects negative amounts', () => {
    expect(() => minorToAmountString(-1n, 2)).toThrow(AmountParseError);
  });
});
