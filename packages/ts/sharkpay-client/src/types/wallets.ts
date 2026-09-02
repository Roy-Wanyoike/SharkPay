/**
 * Types projected from `contracts/openapi/v1/wallets.yaml`.
 *
 * Wallets are multi-currency balance containers. Balances are projections
 * from `ledger.posting.committed.v1` + the hold ledger; the ledger remains
 * the sole authority (docs/DATA-MODEL.md §3.3, docs/STATE-MACHINES.md §5).
 */

import type { Currency, Money, Page } from './common.js';

/** Wallet id (`wal_...`). */
export type WalletId = string;

/** Pattern for `wal_` ids (wallets.yaml `WalletId` / `Wallet.id`). */
export const WALLET_ID_PATTERN = /^wal_[0-9A-Za-z]{20,}$/;

/** Type guard for {@link WalletId}. */
export function isWalletId(value: unknown): value is WalletId {
  return typeof value === 'string' && WALLET_ID_PATTERN.test(value);
}

/**
 * Wallet lifecycle (docs/STATE-MACHINES.md §5). `active ⇄ frozen` (freeze by
 * compliance only), `active → closed` (zero balances only); no delete.
 */
export type WalletStatus = 'active' | 'frozen' | 'closed';

/** Runtime list of {@link WalletStatus} values. */
export const WALLET_STATUSES: readonly WalletStatus[] = ['active', 'frozen', 'closed'];

/** Type guard for {@link WalletStatus}. */
export function isWalletStatus(value: unknown): value is WalletStatus {
  return typeof value === 'string' && (WALLET_STATUSES as readonly string[]).includes(value);
}

/**
 * Balance partitions. `available` = spendable now (never negative);
 * `pending` = in-flight incoming; `held` = reserved by in-flight
 * payments/payouts/transfers.
 */
export interface WalletBalances {
  available: Money;
  pending: Money;
  held: Money;
}

interface WalletBase {
  id: WalletId;
  /** Owning principal (UUID). */
  principal_id: string;
  currency: Currency;
  status: WalletStatus;
  balances: WalletBalances;
  created_at: string;
}

/** A wallet in the terminal `closed` state (`closed_at` present only here). */
export interface ClosedWallet extends WalletBase {
  status: 'closed';
  closed_at: string;
}

/** A wallet in the `active` or `frozen` states. */
export interface OpenWallet extends WalletBase {
  status: Exclude<WalletStatus, 'closed'>;
}

/**
 * A multi-currency balance container (one wallet per principal per
 * currency). Discriminated union on `status`: `closed_at` is only present
 * when `status` is `closed` (wallets.yaml `Wallet.closed_at`).
 */
export type Wallet = ClosedWallet | OpenWallet;

/** Page of wallets (GET /wallets). */
export type WalletList = Page<Wallet>;

/** Query for GET /wallets. */
export type ListWalletsQuery = {
  /** Filter by owning principal (UUID). */
  principal_id?: string | undefined;
  currency?: Currency | undefined;
  status?: WalletStatus | undefined;
  /** Page size (1..100, default 50). */
  limit?: number | undefined;
  /** Opaque cursor from a previous page's `next_cursor`. */
  cursor?: string | undefined;
};

/** Ledger journal entry types (docs/DATA-MODEL.md §3.1). */
export type EntryType = 'capture' | 'hold' | 'release' | 'reversal' | 'fee' | 'fx' | 'adjustment';

/** Runtime list of {@link EntryType} values. */
export const ENTRY_TYPES: readonly EntryType[] = [
  'capture',
  'hold',
  'release',
  'reversal',
  'fee',
  'fx',
  'adjustment',
];

/** Type guard for {@link EntryType}. */
export function isEntryType(value: unknown): value is EntryType {
  return typeof value === 'string' && (ENTRY_TYPES as readonly string[]).includes(value);
}

/** Owning domain of the journal entry. */
export type LedgerSource = 'payments' | 'payouts' | 'transfers' | 'fx' | 'fees' | 'ops';

/** Runtime list of {@link LedgerSource} values. */
export const LEDGER_SOURCES: readonly LedgerSource[] = [
  'payments',
  'payouts',
  'transfers',
  'fx',
  'fees',
  'ops',
];

/** Type guard for {@link LedgerSource}. */
export function isLedgerSource(value: unknown): value is LedgerSource {
  return typeof value === 'string' && (LEDGER_SOURCES as readonly string[]).includes(value);
}

/** Posting direction: debit decreases a wallet balance; credit increases it. */
export type StatementDirection = 'debit' | 'credit';

/**
 * One wallet line of a posted journal entry (GET /wallets/{id}/statement).
 * Entries are immutable; corrections appear as compensation
 * (reversal/adjustment) lines.
 */
export interface StatementEntry {
  /** Posting line id (ledger `postings.id`). */
  id: string;
  /** Journal entry id (UUID). */
  entry_id: string;
  entry_type: EntryType;
  direction: StatementDirection;
  amount: Money;
  /** Wallet available balance after this entry (in ledger order). */
  balance_after: Money;
  source: LedgerSource;
  /** Id of the business object (payment/payout/transfer/...) this posting belongs to (UUID). */
  source_ref: string;
  /** Operator/system note (adjustments, reversals). */
  reason?: string;
  created_at: string;
}

/** Page of statement entries (GET /wallets/{id}/statement). */
export type StatementList = Page<StatementEntry>;

/** Query for GET /wallets/{id}/statement. */
export type StatementQuery = {
  limit?: number | undefined;
  cursor?: string | undefined;
};
