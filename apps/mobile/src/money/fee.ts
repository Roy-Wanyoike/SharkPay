/**
 * Client-side fee ESTIMATES for the Send/Payouts confirm steps.
 *
 * These mirror the V1 default schedules of the server-side policies exactly
 * (they are product-owned numbers, identical in both places):
 *
 * - payments: services/payments/src/main/java/com/sharkpay/payments/domain/
 *     FeeSchedules.java — honeycoin KES 50 bps (min 100) / USDC 60 bps
 *     (min 100); mpesa KES 250 bps (min 100, max 5 000); bank KES/USD/EUR/GBP
 *     30 bps (min 500); on_chain USDC/USDT 80 bps (min 100 000).
 *     bps share uses the money library's largest-remainder allocation
 *     ([bps, 10 000−bps] over 10 000; with two parts the leftover unit goes
 *     to the fee when its remainder ≥ the other part's, ties → fee).
 * - payouts: services/payouts/src/main/java/com/sharkpay/payouts/domain/
 *     PayoutFeePolicy.java — mpesa 5 500 flat + 100 bps (non-refundable
 *     min(flat, fee)); bank 3 000 flat + 50 bps; on_chain 250 000 flat +
 *     25 bps. bps share is a plain floor.
 *
 * The estimate is a UI affordance ONLY: the authoritative fee is computed
 * server-side at intent creation and is always shown on the receipt
 * (the created Payment/Payout response). All arithmetic is exact bigint.
 */

import type { Currency, PayoutRail, Rail } from '../api/types';

export interface FeePolicy {
  rail: Rail;
  currency: Currency;
  /** Fee in basis points of the amount (0..10 000). */
  bps: number;
  /** Flat component, minor units (≥ 0). */
  fixedMinor: bigint;
  /** Lower clamp, minor units (≥ 0, applied after the sum). */
  minimumMinor: bigint;
  /** Upper clamp, minor units; `null` = unbounded. */
  maximumMinor: bigint | null;
}

type RailSchedule = Partial<Record<Currency, FeePolicy>>;

function policy(rail: Rail, currency: Currency, bps: number, min: number, max: number | null): FeePolicy {
  return { rail, currency, bps, fixedMinor: 0n, minimumMinor: BigInt(min), maximumMinor: max === null ? null : BigInt(max) };
}

/** Payment fee table — mirrors services/payments domain FeeSchedules V1 defaults. */
export const PAYMENT_FEE_SCHEDULE: Readonly<Record<Rail, RailSchedule>> = {
  honeycoin: {
    KES: policy('honeycoin', 'KES', 50, 100, null),
    USDC: policy('honeycoin', 'USDC', 60, 100, null),
  },
  mpesa: {
    KES: policy('mpesa', 'KES', 250, 100, 5_000),
  },
  bank: {
    KES: policy('bank', 'KES', 30, 500, null),
    USD: policy('bank', 'USD', 30, 500, null),
    EUR: policy('bank', 'EUR', 30, 500, null),
    GBP: policy('bank', 'GBP', 30, 500, null),
  },
  on_chain: {
    USDC: policy('on_chain', 'USDC', 80, 100_000, null),
    USDT: policy('on_chain', 'USDT', 80, 100_000, null),
  },
};

/**
 * Canonical rail evaluation order, mirroring payments domain
 * `Rail.canonicalOrder()` — used when a create request carries no rail hint:
 * the first rail whose schedule serves the currency is the deterministic
 * default.
 */
export const CANONICAL_RAIL_ORDER: readonly Rail[] = ['honeycoin', 'mpesa', 'bank', 'on_chain'];

/** The payment fee policy for a rail/currency pair, when one exists. */
export function paymentFeePolicy(rail: Rail, currency: Currency): FeePolicy | null {
  return PAYMENT_FEE_SCHEDULE[rail][currency] ?? null;
}

/** Deterministic default rail for a currency (first in canonical order). */
export function defaultPaymentRailFor(currency: Currency): Rail | null {
  for (const rail of CANONICAL_RAIL_ORDER) {
    if (paymentFeePolicy(rail, currency) !== null) {
      return rail;
    }
  }
  return null;
}

/**
 * Largest-remainder bps share, mirroring the money library's
 * `allocate([bps, 10 000 − bps], 10 000)[0]`: floor(amount·bps/10 000) plus
 * the single leftover minor unit when the fee part's fractional remainder is
 * the largest (remainder ≥ 5 000; ties → fee, the lower index).
 */
export function bpsShareLargestRemainder(amountMinor: bigint, bps: number): bigint {
  if (bps <= 0) {
    return 0n;
  }
  if (bps >= 10_000) {
    return amountMinor;
  }
  const scaled = amountMinor * BigInt(bps);
  const quotient = scaled / 10_000n;
  const remainder = scaled % 10_000n;
  // Tie (remainder == 5_000) goes to the fee part (lower index) — see the
  // FeePolicy javadoc's documented rounding policy.
  return quotient + (remainder >= 5_000n ? 1n : 0n);
}

/**
 * Estimates the payment fee for a positive amount on a rail/currency pair
 * (exact bigint; mirrors services/payments FeePolicy.computeFee).
 * Returns `null` when no schedule serves the pair (the rail hint is invalid
 * for that currency and the server would reject the create).
 */
export function estimatePaymentFee(
  amountMinor: bigint,
  rail: Rail,
  currency: Currency,
): bigint | null {
  if (amountMinor <= 0n) {
    throw new RangeError('fee applies to positive amounts only');
  }
  const schedule = paymentFeePolicy(rail, currency);
  if (schedule === null) {
    return null;
  }
  let fee = bpsShareLargestRemainder(amountMinor, schedule.bps) + schedule.fixedMinor;
  if (fee < schedule.minimumMinor) {
    fee = schedule.minimumMinor;
  }
  if (schedule.maximumMinor !== null && fee > schedule.maximumMinor) {
    fee = schedule.maximumMinor;
  }
  return fee;
}

// ─── payouts ─────────────────────────────────────────────────────────────────

export interface PayoutRailFee {
  rail: PayoutRail;
  /** Flat component, minor units. */
  flatMinor: bigint;
  /** Per-myriad (bps) component of the amount. */
  bps: number;
}

/**
 * Payout fee table — mirrors services/payouts domain PayoutFeePolicy.defaults():
 * `fee = flatMinor + floor(amountMinor * bps / 10_000)`, and on a RETURNED
 * payout the non-refundable portion is `min(flatMinor, fee)`.
 */
export const PAYOUT_FEE_SCHEDULE: Readonly<Record<PayoutRail, PayoutRailFee>> = {
  mpesa: { rail: 'mpesa', flatMinor: 5_500n, bps: 100 },
  bank: { rail: 'bank', flatMinor: 3_000n, bps: 50 },
  on_chain: { rail: 'on_chain', flatMinor: 250_000n, bps: 25 },
};

export interface PayoutFeeEstimate {
  /** Estimated total fee charged at hold time. */
  feeMinor: bigint;
  /** Non-refundable portion retained if the payout is returned. */
  nonRefundableMinor: bigint;
}

/** Estimates the payout fee for a positive amount on a rail. */
export function estimatePayoutFee(amountMinor: bigint, rail: PayoutRail): PayoutFeeEstimate {
  if (amountMinor <= 0n) {
    throw new RangeError('fee applies to positive amounts only');
  }
  const schedule = PAYOUT_FEE_SCHEDULE[rail];
  const bpsPart = (amountMinor * BigInt(schedule.bps)) / 10_000n; // floor, per the server policy
  const feeMinor = schedule.flatMinor + bpsPart;
  const nonRefundableMinor =
    schedule.flatMinor < feeMinor ? schedule.flatMinor : feeMinor;
  return { feeMinor, nonRefundableMinor };
}
