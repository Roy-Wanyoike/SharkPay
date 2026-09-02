/**
 * Vendored, minimal projection of the SharkPay /v1 contracts.
 *
 * The typed TS SDK lives at `packages/ts/sharkpay-client` in the monorepo, but
 * apps/mobile deliberately does NOT depend on it (no workspace link is wired
 * yet — see app README "Monorepo integration"). Instead the type shapes the
 * mobile app actually consumes are mirrored here, 1:1 with:
 *
 *   - packages/ts/sharkpay-client/src/types/common.ts   (money, errors, pages)
 *   - packages/ts/sharkpay-client/src/types/wallets.ts   (wallets, statement)
 *   - packages/ts/sharkpay-client/src/types/payments.ts  (payment intents)
 *   - packages/ts/sharkpay-client/src/types/payouts.ts   (payouts)
 *
 * When a workspace link lands, this file should shrink to a re-export.
 */

// ─── common.yaml ─────────────────────────────────────────────────────────────

/**
 * Supported currency at V1 (docs/PRD.md §7 D2).
 * Fiat has exponent 2; stablecoins 6.
 */
export type Currency = 'KES' | 'USD' | 'EUR' | 'GBP' | 'USDC' | 'USDT';

/** Runtime list of {@link Currency} values (validation / exhaustive switches). */
export const CURRENCIES: readonly Currency[] = ['KES', 'USD', 'EUR', 'GBP', 'USDC', 'USDT'];

/** Type guard for {@link Currency}. */
export function isCurrency(value: unknown): value is Currency {
  return typeof value === 'string' && (CURRENCIES as readonly string[]).includes(value);
}

/**
 * Integer-only money (docs/API-CONTRACTS.md §1.6). `amount_minor` is signed
 * minor units; `exponent` is the currency's minor-unit exponent (2 for
 * KES/USD/EUR/GBP, 6 for USDC/USDT). Never floats.
 *
 * ## int64 safety note (mirrors the SDK)
 *
 * `amount_minor` is `int64` on the wire. `JSON.parse` yields a JS `number`,
 * exact only within ±(2^53 − 1) minor units. Every display path in this app
 * converts to `bigint` via `toBigIntMoney` (which REFUSES unsafe integers)
 * before formatting — see src/money/format.ts.
 */
export interface Money {
  /** Signed minor units (`int64` on the wire). */
  amount_minor: number;
  currency: Currency;
  /** Minor-unit exponent of the currency (0..18). */
  exponent: number;
}

/** `Money` with `amount_minor` as an exact `bigint` (display/arithmetic form). */
export interface BigIntMoney {
  amount_minor: bigint;
  currency: Currency;
  exponent: number;
}

/** Cursor-paginated page (common.yaml `?limit=` 1..100, `?cursor=`). */
export interface Page<T> {
  items: T[];
  next_cursor?: string | null;
}

/** Machine-readable error body inside {@link ErrorEnvelope}. */
export interface ErrorBody {
  code: string;
  /** Human-readable explanation. */
  message: string;
  /** Correlates with the X-Request-Id response header and server logs. */
  request_id: string;
  /** Optional machine-readable context (e.g. available_minor, requested_minor). */
  details?: Record<string, unknown>;
}

/** The single error envelope used by every endpoint (docs/API-CONTRACTS.md §1.4). */
export interface ErrorEnvelope {
  error: ErrorBody;
}

/** Caller-supplied key/value metadata stored with the resource. */
export type Metadata = Record<string, unknown>;

// ─── wallets.yaml ────────────────────────────────────────────────────────────

/** Wallet id (`wal_...`). */
export type WalletId = string;

/**
 * Wallet lifecycle (docs/STATE-MACHINES.md §5). `active ⇄ frozen` (freeze by
 * compliance only), `active → closed` (zero balances only); no delete.
 */
export type WalletStatus = 'active' | 'frozen' | 'closed';

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

/** A multi-currency balance container (one wallet per principal per currency). */
export interface Wallet {
  id: WalletId;
  /** Owning principal (UUID). */
  principal_id: string;
  currency: Currency;
  status: WalletStatus;
  balances: WalletBalances;
  created_at: string;
  /** Present only in the terminal `closed` state. */
  closed_at?: string;
}

/** Query for GET /wallets. */
export interface ListWalletsQuery {
  principal_id?: string;
  currency?: Currency;
  status?: WalletStatus;
  /** Page size (1..100, default 50). */
  limit?: number;
  /** Opaque cursor from a previous page's `next_cursor`. */
  cursor?: string;
}

/** Ledger journal entry types (docs/DATA-MODEL.md §3.1). */
export type EntryType = 'capture' | 'hold' | 'release' | 'reversal' | 'fee' | 'fx' | 'adjustment';

/** Owning domain of a journal entry. */
export type LedgerSource = 'payments' | 'payouts' | 'transfers' | 'fx' | 'fees' | 'ops';

/** Posting direction: debit decreases a wallet balance; credit increases it. */
export type StatementDirection = 'debit' | 'credit';

/** One wallet line of a posted journal entry (GET /wallets/{id}/statement). */
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
  /** Id of the business object (payment/payout/transfer/…) this posting belongs to. */
  source_ref: string;
  /** Operator/system note (adjustments, reversals). */
  reason?: string;
  created_at: string;
}

/** Query for GET /wallets/{id}/statement. */
export interface StatementQuery {
  limit?: number;
  cursor?: string;
}

// ─── payments.yaml ───────────────────────────────────────────────────────────

/** Payment intent id (`pay_...`). */
export type PaymentId = string;

/** Payment rail / provider family. `rail` on create is a hint; the router decides. */
export type Rail = 'honeycoin' | 'mpesa' | 'bank' | 'on_chain';

/** Runtime list of {@link Rail} values. */
export const RAILS: readonly Rail[] = ['honeycoin', 'mpesa', 'bank', 'on_chain'];

/**
 * Payment intent states (docs/STATE-MACHINES.md §1):
 * CREATED → PENDING_PROVIDER → PROCESSING → SUCCEEDED; BLOCKED / CANCELLED /
 * FAILED / EXPIRED / REVERSED are the other reachable states.
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

/** Terminal payment states — stable once reached. */
export type TerminalPaymentState = Extract<
  PaymentState,
  'SUCCEEDED' | 'FAILED' | 'EXPIRED' | 'REVERSED' | 'BLOCKED' | 'CANCELLED'
>;

/** A payment intent (payments.yaml Payment; `failure_reason` only on FAILED). */
export interface Payment {
  id: PaymentId;
  state: PaymentState;
  amount: Money;
  /** Fee computed at intent creation (fee schedule per rail/currency). */
  fee: Money;
  /** Wallet the collected funds settle into (`wal_...`). */
  destination_wallet: WalletId;
  rail: Rail;
  metadata?: Metadata;
  /** V1 always `{ type: 'none' }` (provider hand-off is server-side). */
  next_action: { type: 'none' };
  /** Reason included in the `payment.failed` payload; present iff state FAILED. */
  failure_reason?: string;
  /** Provider-side transfer reference, set after routing. */
  provider_ref?: string;
  /** TTL for confirmation; expiry only happens from PENDING_PROVIDER. */
  expires_at: string;
  created_at: string;
  updated_at?: string;
}

/** Request body for POST /payments (createPayment). */
export interface PaymentCreateRequest {
  /** Amount to collect, in minor units (`int64`, minimum 1). */
  amount_minor: number;
  currency: Currency;
  destination_wallet: WalletId;
  /** Optional rail hint; the router may choose a different provider. */
  rail?: Rail;
  metadata?: Metadata;
  /** Intent TTL in seconds before it expires unconfirmed (60..86400, default 900). */
  expires_in_seconds?: number;
}

/** Query for GET /payments (listPayments). */
export interface ListPaymentsQuery {
  state?: PaymentState;
  principal_id?: string;
  created_from?: string;
  created_to?: string;
  limit?: number;
  cursor?: string;
}

// ─── payouts.yaml ────────────────────────────────────────────────────────────

/** Payout id (`pot_...`). */
export type PayoutId = string;

/** Payout rail. Payouts go out (wallet → external), so no honeycoin at V1. */
export type PayoutRail = 'mpesa' | 'bank' | 'on_chain';

/** Runtime list of {@link PayoutRail} values. */
export const PAYOUT_RAILS: readonly PayoutRail[] = ['mpesa', 'bank', 'on_chain'];

/**
 * Payout states (docs/STATE-MACHINES.md §2):
 * CREATED → PENDING_RISK → PROCESSING → SENT → SUCCEEDED, with BLOCKED /
 * FAILED / RETURNED / CANCELLED reachable per the state machine.
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

/** External payout destination; discriminated by `type`. */
export type PayoutDestination = MpesaDestination | BankDestination | OnChainDestination;

/** A payout (`failure_reason` on FAILED, `return_reason` on RETURNED). */
export interface Payout {
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
  /** TTL before auto-cancellation when the provider has not accepted. */
  expires_at?: string;
  created_at: string;
  updated_at?: string;
  /** Present iff state FAILED. */
  failure_reason?: string;
  /** Present iff state RETURNED. */
  return_reason?: string;
}

/** Request body for POST /payouts (createPayout). */
export interface PayoutCreateRequest {
  source_wallet: WalletId;
  /** `int64`, minimum 1. */
  amount_minor: number;
  currency: Currency;
  destination: PayoutDestination;
  /** Optional rail hint; must be compatible with the destination type. */
  rail?: PayoutRail;
  metadata?: Metadata;
  /** TTL in seconds before auto-cancellation (60..86400, default 900). */
  expires_in_seconds?: number;
}

/**
 * NOTE (contract gap, mirrored from the SDK): the v1 payouts spec has NO
 * `GET /payouts` list endpoint — only create, get-by-id and cancel. The
 * mobile app therefore tracks payouts created on-device in its store and can
 * refresh each by id; a wallet statement is the authoritative per-wallet
 * history surface.
 */
export interface GetPayoutQuery {
  id: PayoutId;
}
