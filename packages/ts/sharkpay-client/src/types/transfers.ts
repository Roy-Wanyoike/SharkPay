/**
 * Types projected from `contracts/openapi/v1/transfers.yaml`.
 *
 * Transfers — instant wallet-to-wallet money movement inside SharkPay.
 * Internal transfers commit as a single atomic ledger transaction (no
 * rail). V1 execution is synchronous: a 201 response carries the terminal
 * state (`SUCCEEDED`, or `FAILED` for pre-flight rejection that never
 * partially posts). The ledger journal entry id is returned as `entry_id`.
 * Required scope: `transfers:write`.
 *
 * Note: the v1 contract exposes only `POST /transfers` — there is no
 * get/list endpoint — so this SDK exposes exactly `transfers.create`.
 */

import type { Currency, Metadata, Money } from './common.js';
import type { WalletId } from './wallets.js';

/** Transfer id (`trf_...`). */
export type TransferId = string;

/** Pattern for `trf_` ids (transfers.yaml `Transfer.id`). */
export const TRANSFER_ID_PATTERN = /^trf_[0-9A-Za-z]{20,}$/;

/** Type guard for {@link TransferId}. */
export function isTransferId(value: unknown): value is TransferId {
  return typeof value === 'string' && TRANSFER_ID_PATTERN.test(value);
}

/**
 * Transfer states (docs/STATE-MACHINES.md §3): CREATED → SUCCEEDED or
 * CREATED → FAILED (pre-flight rejection; never partially posted).
 * V1 execution is synchronous, so responses are terminal.
 */
export type TransferState = 'CREATED' | 'SUCCEEDED' | 'FAILED';

/** Runtime list of {@link TransferState} values. */
export const TRANSFER_STATES: readonly TransferState[] = ['CREATED', 'SUCCEEDED', 'FAILED'];

/** Type guard for {@link TransferState}. */
export function isTransferState(value: unknown): value is TransferState {
  return typeof value === 'string' && (TRANSFER_STATES as readonly string[]).includes(value);
}

interface TransferBase {
  id: TransferId;
  state: TransferState;
  source_wallet: WalletId;
  destination_wallet: WalletId;
  amount: Money;
  /** Transfer fee; V1 internal transfers are zero-fee. */
  fee: Money;
  metadata?: Metadata;
  created_at: string;
}

/** An in-flight transfer (V1 responses are terminal; kept for completeness). */
export interface TransferCreated extends TransferBase {
  state: 'CREATED';
}

/**
 * A committed transfer — `entry_id` (the ledger journal entry backing this
 * transfer) is present once committed, i.e. when `state` is `SUCCEEDED`.
 */
export interface TransferSucceeded extends TransferBase {
  state: 'SUCCEEDED';
  /** Ledger journal entry id backing this transfer (UUID). */
  entry_id: string;
}

/** A pre-flight-rejected transfer; never partially posted. */
export interface TransferFailed extends TransferBase {
  state: 'FAILED';
  failure_reason: string;
}

/**
 * An internal wallet-to-wallet transfer. Discriminated union on `state`:
 * `SUCCEEDED` ⇒ `entry_id` (required), `FAILED` ⇒ `failure_reason`
 * (required).
 */
export type Transfer = TransferCreated | TransferSucceeded | TransferFailed;

/** Request body for POST /transfers (createTransfer). */
export interface TransferCreateRequest {
  source_wallet: WalletId;
  destination_wallet: WalletId;
  /** `int64`, minimum 1. */
  amount_minor: number;
  currency: Currency;
  metadata?: Metadata | undefined;
}
