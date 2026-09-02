/**
 * Fee estimation parity with the SERVER policies (services/payments
 * FeePolicy / services/payouts PayoutFeePolicy): the app must show the same
 * fee the backend will charge — exact bigint math, clamps included.
 */

import { describe, expect, it } from '@jest/globals';

import {
  bpsShareLargestRemainder,
  defaultPaymentRailFor,
  estimatePaymentFee,
  estimatePayoutFee,
} from '../fee';

describe('bpsShareLargestRemainder', () => {
  it('computes the exact bps share for clean divisions', () => {
    expect(bpsShareLargestRemainder(150000n, 50)).toBe(750n);
    expect(bpsShareLargestRemainder(10000n, 250)).toBe(250n);
  });

  it('rounds the at-most-one leftover unit by largest remainder (ties to the fee)', () => {
    // 999 * 3333 = 3,329,667 → quotient 332, remainder 9,667 ≥ 5,000 → 333
    expect(bpsShareLargestRemainder(999n, 3333)).toBe(333n);
    // 999 * 2500 = 2,497,500 → quotient 249, remainder 7,500 → 250
    expect(bpsShareLargestRemainder(999n, 2500)).toBe(250n);
    // 999 * 1500 = 1,498,500 → quotient 149, remainder 8,500 → 150
    expect(bpsShareLargestRemainder(999n, 1500)).toBe(150n);
    // remainder below the tie point rounds DOWN: 999 * 4999 = 4,994,001 → q 499, r 4,001 → 499
    expect(bpsShareLargestRemainder(999n, 4999)).toBe(499n);
  });

  it('handles the degenerate bounds exactly', () => {
    expect(bpsShareLargestRemainder(100n, 0)).toBe(0n);
    expect(bpsShareLargestRemainder(100n, 10_000)).toBe(100n);
  });
});

describe('estimatePaymentFee', () => {
  it('matches the honeycoin KES schedule (50 bps, min 100)', () => {
    expect(estimatePaymentFee(150000n, 'honeycoin', 'KES')).toBe(750n);
  });

  it('applies the minimum clamp for small amounts', () => {
    // 50 bps of 1,000 = 5 → clamped to the 100 minimum
    expect(estimatePaymentFee(1000n, 'honeycoin', 'KES')).toBe(100n);
    // bank: 30 bps of 10,000 = 30 → clamped to the 500 minimum
    expect(estimatePaymentFee(10000n, 'bank', 'USD')).toBe(500n);
  });

  it('applies the maximum clamp for large mpesa amounts', () => {
    // 250 bps of 100,000,000 = 2,500,000 → clamped to 5,000
    expect(estimatePaymentFee(100000000n, 'mpesa', 'KES')).toBe(5000n);
  });

  it('returns null when no schedule serves the pair (server would reject)', () => {
    expect(estimatePaymentFee(150000n, 'honeycoin', 'USD')).toBeNull();
    expect(estimatePaymentFee(150000n, 'mpesa', 'USD')).toBeNull();
  });

  it('throws on non-positive amounts', () => {
    expect(() => estimatePaymentFee(0n, 'honeycoin', 'KES')).toThrow(RangeError);
    expect(() => estimatePaymentFee(-1n, 'honeycoin', 'KES')).toThrow(RangeError);
  });
});

describe('defaultPaymentRailFor', () => {
  it('follows the canonical rail order per currency', () => {
    expect(defaultPaymentRailFor('KES')).toBe('honeycoin');
    expect(defaultPaymentRailFor('USDC')).toBe('honeycoin');
    expect(defaultPaymentRailFor('USD')).toBe('bank');
    expect(defaultPaymentRailFor('EUR')).toBe('bank');
    expect(defaultPaymentRailFor('GBP')).toBe('bank');
    expect(defaultPaymentRailFor('USDT')).toBe('on_chain');
  });
});

describe('estimatePayoutFee', () => {
  it('matches the payouts server policy: flat + floor(bps share)', () => {
    // mpesa: flat 5,500 + floor(100,000 * 100 / 10,000) = 5,500 + 1,000
    expect(estimatePayoutFee(100000n, 'mpesa')).toEqual({
      feeMinor: 6500n,
      nonRefundableMinor: 5500n,
    });
    // bank: flat 3,000 + floor(200,000 * 50 / 10,000) = 3,000 + 1,000
    expect(estimatePayoutFee(200000n, 'bank')).toEqual({
      feeMinor: 4000n,
      nonRefundableMinor: 3000n,
    });
    // on_chain: flat 250,000 + floor(10,000,000 * 25 / 10,000) = 250,000 + 25,000
    expect(estimatePayoutFee(10000000n, 'on_chain')).toEqual({
      feeMinor: 275000n,
      nonRefundableMinor: 250000n,
    });
  });

  it('caps the non-refundable portion at the total fee (flat exceeds bps share)', () => {
    // Tiny amount: fee = 5,500 + floor(100 * 100 / 10,000 = 1) = 5,501;
    // non-refundable = min(flat, fee) = 5,500
    expect(estimatePayoutFee(100n, 'mpesa')).toEqual({
      feeMinor: 5501n,
      nonRefundableMinor: 5500n,
    });
  });

  it('throws on non-positive amounts', () => {
    expect(() => estimatePayoutFee(0n, 'mpesa')).toThrow(RangeError);
  });
});
