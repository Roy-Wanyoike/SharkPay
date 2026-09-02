/**
 * Types projected from `contracts/openapi/v1/payments.yaml`.
 *
 * Payment intents — collect money into SharkPay wallets. Creation runs
 * synchronously through risk evaluation, hold placement and provider
 * hand-off, so the returned intent is already in `PENDING_PROVIDER` (or a
 * terminal/blocked state). Required scope: `payments:write` for POST,
 * `payments:read` for GET.
 */

import type { Currency, Metadata, Money, Page } from './common.js';
import type { WalletId } from './wallets.js';

/** Payment intent id (`pay_...`). */
export type PaymentId = string;

/** Pattern for `pay_` ids (payments.yaml `PaymentId` / `Payment.id`). */
export const PAYMENT_ID_PATTERN = /^pay_[0-9A-Za-z]{20,}$/;

/** Type guard for {@link PaymentId}. */
export function isPaymentId(value: unknown): value is PaymentId {
  return typeof value === 'string' && PAYMENT_ID_PATTERN.test(value);
}

/**
 * Payment rail / provider family. `rail` on create is a hint; the router
 * makes the final provider choice.
 */
export type Rail = 'honeycoin' | 'mpesa' | 'bank' | 'on_chain';

/** Runtime list of {@link Rail} values. */
export const RAILS: readonly Rail[] = ['honeycoin', 'mpesa', 'bank', 'on_chain'];

/** Type guard for {@link Rail}. */
export function isRail(value: unknown): value is Rail {
  return typeof value === 'string' && (RAILS as readonly string[]).includes(value);
}

/**
 * Payment intent states (docs/STATE-MACHINES.md §1):
 * CREATED → PENDING_PROVIDER → PROCESSING → SUCCEEDED;
 * BLOCKED / CANCELLED / FAILED / EXPIRED / REVERSED are the other reachable
 * states. State values only append over time (additive-only /v1 policy).
 */
export type PaymentState =
  | 'CREATED'
  | 'PENDING_PROVIDER'
  | 'PROCESSING'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'EXPIRED'
  | 'REVERSED'
  | 'BLOCKED'
  | 'CANCELLED';

/** Runtime list of {@link PaymentState} values. */
export const PAYMENT_STATES: readonly PaymentState[] = [
  'CREATED',
  'PENDING_PROVIDER',
  'PROCESSING',
  'SUCCEEDED',
  'FAILED',
  'EXPIRED',
  'REVERSED',
  'BLOCKED',
  'CANCELLED',
];

/** Type guard for {@link PaymentState}. */
export function isPaymentState(value: unknown): value is PaymentState {
  return typeof value === 'string' && (PAYMENT_STATES as readonly string[]).includes(value);
}

/**
 * Terminal payment states — stable once reached (payments.yaml
 * `getPayment` description).
 */
export type TerminalPaymentState =
  | 'SUCCEEDED'
  | 'FAILED'
  | 'EXPIRED'
  | 'REVERSED'
  | 'BLOCKED'
  | 'CANCELLED';

/**
 * What the caller must do next. V1 always `none` (provider hand-off is
 * server-side); additional types may be appended additively.
 */
export interface NextAction {
  type: 'none';
}

interface PaymentBase {
  id: PaymentId;
  state: PaymentState;
  amount: Money;
  /** Fee computed at intent creation (fee schedule per rail/currency). */
  fee: Money;
  /** Wallet the collected funds settle into (`wal_...`). */
  destination_wallet: WalletId;
  rail: Rail;
  metadata?: Metadata;
  next_action: NextAction;
  /** Provider-side transfer reference (e.g. HoneyCoin tx id), set after routing. */
  provider_ref?: string;
  /** TTL for confirmation; expiry only happens from PENDING_PROVIDER. */
  expires_at: string;
  created_at: string;
  updated_at?: string;
}

/**
 * A payment intent in the `FAILED` state. `failure_reason` is present only
 * when `state` is `FAILED` (payments.yaml `Payment.failure_reason`), so it is
 * a required member of this arm of the union.
 */
export interface PaymentFailed extends PaymentBase {
  state: 'FAILED';
  /** Reason included in the `payment.failed` webhook. */
  failure_reason: string;
}

/** A payment intent in any state other than `FAILED`. */
export interface PaymentOther extends PaymentBase {
  state: Exclude<PaymentState, 'FAILED'>;
}

/**
 * A payment intent. Discriminated union on `state`: narrowing
 * `payment.state === 'FAILED'` gives access to `failure_reason` as a
 * non-optional `string`.
 */
export type Payment = PaymentFailed | PaymentOther;

/** Request body for POST /payments (createPayment). */
export interface PaymentCreateRequest {
  /** Amount to collect, in minor units (`int64`, minimum 1). */
  amount_minor: number;
  currency: Currency;
  destination_wallet: WalletId;
  /** Optional rail hint; the router may choose a different provider. */
  rail?: Rail | undefined;
  metadata?: Metadata | undefined;
  /** Intent TTL in seconds before it expires unconfirmed (60..86400, default 900). */
  expires_in_seconds?: number | undefined;
}

/** Page of payment intents (GET /payments). */
export type PaymentList = Page<Payment>;

/** Query for GET /payments (listPayments). */
export type ListPaymentsQuery = {
  /** Filter by intent state. */
  state?: PaymentState | undefined;
  /** Filter by owning principal (UUID). */
  principal_id?: string | undefined;
  /** Include intents created at or after this instant (RFC 3339). */
  created_from?: string | undefined;
  /** Include intents created before this instant (RFC 3339). */
  created_to?: string | undefined;
  /** Page size (1..100, default 50). */
  limit?: number | undefined;
  /** Opaque cursor from a previous page's `next_cursor`. */
  cursor?: string | undefined;
};
