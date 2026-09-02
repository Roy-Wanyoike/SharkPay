/**
 * Types projected from `contracts/openapi/v1/payouts.yaml`.
 *
 * Payouts — withdraw money from a SharkPay wallet to an external
 * destination. Funds are held before provider initiation; a returned payout
 * posts a compensation entry and re-credits the wallet (minus
 * non-refundable rail fees where applicable). Required scope:
 * `payouts:write` for POST, `payouts:read` for GET.
 *
 * Note: the v1 contract has no `GET /payouts` list endpoint — only create,
 * get-by-id and cancel — so this SDK exposes exactly those.
 */

import type { Currency, Metadata, Money } from './common.js';
import type { WalletId } from './wallets.js';

/** Payout id (`pot_...`). */
export type PayoutId = string;

/** Pattern for `pot_` ids (payouts.yaml `PayoutId` / `Payout.id`). */
export const PAYOUT_ID_PATTERN = /^pot_[0-9A-Za-z]{20,}$/;

/** Type guard for {@link PayoutId}. */
export function isPayoutId(value: unknown): value is PayoutId {
  return typeof value === 'string' && PAYOUT_ID_PATTERN.test(value);
}

/**
 * Payout rail. Payouts go out (wallet → external), so honeycoin is not a
 * payout destination rail at V1.
 */
export type PayoutRail = 'mpesa' | 'bank' | 'on_chain';

/** Runtime list of {@link PayoutRail} values. */
export const PAYOUT_RAILS: readonly PayoutRail[] = ['mpesa', 'bank', 'on_chain'];

/** Type guard for {@link PayoutRail}. */
export function isPayoutRail(value: unknown): value is PayoutRail {
  return typeof value === 'string' && (PAYOUT_RAILS as readonly string[]).includes(value);
}

/** Payout destination rail type (used by `payout.*` webhook payloads). */
export type PayoutDestinationType = 'mpesa' | 'bank' | 'on_chain';

/**
 * Payout states (docs/STATE-MACHINES.md §2):
 * CREATED → PENDING_RISK → PROCESSING → SENT → SUCCEEDED, with
 * BLOCKED / FAILED / RETURNED / CANCELLED reachable as shown in the state
 * machine. State values only append over time.
 */
export type PayoutState =
  | 'CREATED'
  | 'PENDING_RISK'
  | 'PROCESSING'
  | 'SENT'
  | 'SUCCEEDED'
  | 'FAILED'
  | 'RETURNED'
  | 'BLOCKED'
  | 'CANCELLED';

/** Runtime list of {@link PayoutState} values. */
export const PAYOUT_STATES: readonly PayoutState[] = [
  'CREATED',
  'PENDING_RISK',
  'PROCESSING',
  'SENT',
  'SUCCEEDED',
  'FAILED',
  'RETURNED',
  'BLOCKED',
  'CANCELLED',
];

/** Type guard for {@link PayoutState}. */
export function isPayoutState(value: unknown): value is PayoutState {
  return typeof value === 'string' && (PAYOUT_STATES as readonly string[]).includes(value);
}

/** M-Pesa destination (mobile money). */
export interface MpesaDestination {
  type: 'mpesa';
  /** Subscriber number, E.164 (e.g. +254712345678). */
  msisdn: string;
}

/** Bank destination. */
export interface BankDestination {
  type: 'bank';
  /** Bank/rail code (per provider's bank directory). */
  bank_code: string;
  account_number: string;
  /** Beneficiary account name (required by some rails). */
  account_name?: string;
  /** ISO 3166-1 alpha-2 country of the bank account. */
  country?: string;
}

/** On-chain destination (stablecoin rails). */
export interface OnChainDestination {
  type: 'on_chain';
  /** EVM network. Values only append over time. */
  network: 'base' | 'ethereum' | 'polygon';
  /** EVM address (hex, 20 bytes, `^0x[0-9a-fA-F]{40}$`). */
  address: string;
}

/**
 * External payout destination; discriminated by `type`
 * (`mpesa` | `bank` | `on_chain`).
 */
export type PayoutDestination = MpesaDestination | BankDestination | OnChainDestination;

interface PayoutBase {
  id: PayoutId;
  state: PayoutState;
  source_wallet: WalletId;
  amount: Money;
  /** Payout fee (non-refundable portion may apply on RETURNED). */
  fee: Money;
  destination: PayoutDestination;
  rail: PayoutRail;
  metadata?: Metadata;
  /** Provider-side transfer reference, set after routing. */
  provider_ref?: string;
  /** TTL before the payout is auto-cancelled if the provider has not accepted it. */
  expires_at?: string;
  created_at: string;
  updated_at?: string;
}

/**
 * A payout in the `FAILED` state — `failure_reason` present only here
 * (payouts.yaml `Payout.failure_reason`).
 */
export interface PayoutFailed extends PayoutBase {
  state: 'FAILED';
  failure_reason: string;
}

/**
 * A payout in the `RETURNED` state — `return_reason` present only here
 * (payouts.yaml `Payout.return_reason`).
 */
export interface PayoutReturned extends PayoutBase {
  state: 'RETURNED';
  return_reason: string;
}

/** A payout in any state other than `FAILED` / `RETURNED`. */
export interface PayoutOther extends PayoutBase {
  state: Exclude<PayoutState, 'FAILED' | 'RETURNED'>;
}

/**
 * A payout. Discriminated union on `state`: `FAILED` ⇒ `failure_reason`,
 * `RETURNED` ⇒ `return_reason` (each required on its arm).
 */
export type Payout = PayoutFailed | PayoutReturned | PayoutOther;

/** Request body for POST /payouts (createPayout). */
export interface PayoutCreateRequest {
  source_wallet: WalletId;
  /** `int64`, minimum 1. */
  amount_minor: number;
  currency: Currency;
  destination: PayoutDestination;
  /** Optional rail hint; must be compatible with the destination type. */
  rail?: PayoutRail | undefined;
  metadata?: Metadata | undefined;
  /** TTL in seconds before auto-cancellation when the provider has not accepted (60..86400, default 900). */
  expires_in_seconds?: number | undefined;
}
