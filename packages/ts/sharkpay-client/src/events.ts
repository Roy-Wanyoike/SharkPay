/**
 * CloudEvent envelope + typed webhook payload unions, projected from
 * `contracts/openapi/v1/webhooks.yaml` (`WebhookEvent` and the
 * `*EventData` schemas) and `contracts/events/events.md`.
 *
 * SharkPay delivers at-least-once: consumers must dedupe on `event.id` and
 * treat `data.state` as monotonic. Webhook payloads use the unversioned
 * catalog type names (`payment.succeeded`); the Kafka counterparts use the
 * versioned topic names (`payments.payment.succeeded.v1`) with the same
 * envelope and payload shapes.
 */

import type { Currency, Money } from './types/common.js';
import type { PaymentId, PaymentState, Rail } from './types/payments.js';
import type { PayoutDestinationType, PayoutId, PayoutState } from './types/payouts.js';
import type { TransferId, TransferState } from './types/transfers.js';
import type { LedgerSource, WalletBalances, WalletId } from './types/wallets.js';
import type { ConversionId, QuoteId, Rate } from './types/fx.js';
import type { CaseId, EventName } from './types/webhooks.js';
import { EVENT_NAMES } from './types/webhooks.js';

/** Payload for `payment.*` events (webhooks.yaml `PaymentEventData`). */
export interface PaymentEventData {
  payment_id: PaymentId;
  /** Payment intent state after the transition. */
  state: PaymentState;
  amount: Money;
  fee: Money;
  destination_wallet: WalletId;
  rail: Rail;
  /** Failure/expiry/reversal reason; present on failed, expired and reversed events. */
  reason?: string;
  /** Ledger journal entry id; present when a ledger entry was posted (UUID). */
  entry_id?: string;
}

/** Payload for `payout.*` events (webhooks.yaml `PayoutEventData`). */
export interface PayoutEventData {
  payout_id: PayoutId;
  /** Payout state after the transition. */
  state: PayoutState;
  amount: Money;
  fee: Money;
  source_wallet: WalletId;
  /** External destination rail type (redacted destination details are available via the API). */
  destination_type: PayoutDestinationType;
  /** Failure/return reason; present on failed and returned events. */
  reason?: string;
  /** Ledger journal entry id; present when a ledger entry was posted (UUID). */
  entry_id?: string;
}

/** Payload for `transfer.succeeded` events (webhooks.yaml `TransferEventData`). */
export interface TransferEventData {
  transfer_id: TransferId;
  state: TransferState;
  amount: Money;
  fee: Money;
  source_wallet: WalletId;
  destination_wallet: WalletId;
  entry_id: string;
}

/** Payload for `fx.quote.locked` events (webhooks.yaml `FxQuoteEventData`). */
export interface FxQuoteEventData {
  quote_id: QuoteId;
  base_currency: Currency;
  quote_currency: Currency;
  rate: Rate;
  expires_at: string;
}

/** Payload for `fx.conversion.executed` events (webhooks.yaml `FxConversionEventData`). */
export interface FxConversionEventData {
  conversion_id: ConversionId;
  quote_id: QuoteId;
  source_amount: Money;
  target_amount: Money;
  entry_id: string;
}

/**
 * Payload for `wallet.balance.changed` events (any balance partition
 * change; webhooks.yaml `WalletBalanceEventData`).
 */
export interface WalletBalanceEventData {
  wallet_id: WalletId;
  principal_id: string;
  currency: Currency;
  balances: WalletBalances;
  /** Domain that caused the change (same value set as wallets.yaml `LedgerSource`). */
  source: LedgerSource;
  /** Id of the business object that caused the change (UUID). */
  source_ref: string;
}

/** Domain that can raise a risk case (webhooks.yaml `RiskCaseEventData.source`). */
export type RiskCaseSource = 'payments' | 'payouts' | 'transfers' | 'fx' | 'wallet' | 'ops';

/** Runtime list of {@link RiskCaseSource} values. */
export const RISK_CASE_SOURCES: readonly RiskCaseSource[] = [
  'payments',
  'payouts',
  'transfers',
  'fx',
  'wallet',
  'ops',
];

/** Type guard for {@link RiskCaseSource}. */
export function isRiskCaseSource(value: unknown): value is RiskCaseSource {
  return typeof value === 'string' && (RISK_CASE_SOURCES as readonly string[]).includes(value);
}

/** Payload for `risk.case.opened` events (Console only; webhooks.yaml `RiskCaseEventData`). */
export interface RiskCaseEventData {
  case_id: CaseId;
  principal_id: string;
  /** Domain that raised the case. */
  source: RiskCaseSource;
  source_ref: string;
  /** Summary of why the case was opened. */
  reason: string;
}

/** Union of every v1 webhook payload type. */
export type EventData =
  | PaymentEventData
  | PayoutEventData
  | TransferEventData
  | FxQuoteEventData
  | FxConversionEventData
  | WalletBalanceEventData
  | RiskCaseEventData;

/**
 * CloudEvents 1.0-aligned envelope delivered to webhook endpoints
 * (webhooks.yaml `WebhookEvent`; identical to the Kafka envelope in
 * contracts/events/events.md modulo the `type` naming). `id` is globally
 * unique (UUID) — dedupe on it.
 */
export interface CloudEvent<Data = EventData> {
  /** Globally unique event id (UUID v7). Consumers dedupe on this value. */
  id: string;
  /** Catalog event name (unversioned on webhooks, e.g. `payment.succeeded`). */
  type: EventName;
  specversion: '1.0';
  /** Producing service (CloudEvents source), e.g. `sharkpay/payments`. */
  source: string;
  /** Id of the entity the event is about (matches `data.*_id`). */
  subject: string;
  /** When the state change occurred (RFC 3339). */
  occurred_at: string;
  data: Data;
}

/** A delivered webhook event: envelope + payload union, discriminable on `type`. */
export type WebhookEvent = CloudEventOf<EventName>;

/**
 * Event name → payload type mapping for the v1 catalog. Use with
 * {@link CloudEventOf} to get fully narrowed events:
 *
 * ```ts
 * const event: CloudEventOf<'payment.succeeded'> = ...;
 * event.data.state; // PaymentState
 * ```
 */
export interface EventPayloadMap {
  'payment.created': PaymentEventData;
  'payment.pending_provider': PaymentEventData;
  'payment.succeeded': PaymentEventData;
  'payment.failed': PaymentEventData;
  'payment.expired': PaymentEventData;
  'payment.reversed': PaymentEventData;
  'payout.created': PayoutEventData;
  'payout.processing': PayoutEventData;
  'payout.sent': PayoutEventData;
  'payout.succeeded': PayoutEventData;
  'payout.failed': PayoutEventData;
  'payout.returned': PayoutEventData;
  'transfer.succeeded': TransferEventData;
  'fx.quote.locked': FxQuoteEventData;
  'fx.conversion.executed': FxConversionEventData;
  'wallet.balance.changed': WalletBalanceEventData;
  'risk.case.opened': RiskCaseEventData;
}

/**
 * A {@link CloudEvent} whose `data` is narrowed to the payload of event name
 * `K` (distributive over unions, so `CloudEventOf<EventName>` is a union of
 * all 17 catalog events, each with its literal `type` discriminant —
 * `event.type === 'risk.case.opened'` narrows `event.data`).
 */
export type CloudEventOf<K extends EventName> = K extends unknown
  ? CloudEvent<EventPayloadMap[K]> & { type: K }
  : never;

const UUID_PATTERN = /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/i;

function requireStringField(record: Record<string, unknown>, field: string): string {
  const value = record[field];
  if (typeof value !== 'string' || value.length === 0) {
    throw new TypeError(`webhook event field '${field}' must be a non-empty string`);
  }
  return value;
}

/**
 * Runtime-parse and validate an unknown JSON value as a {@link WebhookEvent}
 * envelope (required fields, catalog `type`, `specversion: '1.0'`, UUID
 * `id`, object `data`). Payload contents beyond the envelope are trusted
 * once the envelope validates — apply `parseMoney` to money fields if you
 * need runtime guarantees. Throws `TypeError` with a field-specific message.
 */
export function parseWebhookEvent(value: unknown): WebhookEvent {
  if (typeof value !== 'object' || value === null || Array.isArray(value)) {
    throw new TypeError('webhook event must be a JSON object');
  }
  const record = value as Record<string, unknown>;

  const id = requireStringField(record, 'id');
  if (!UUID_PATTERN.test(id)) {
    throw new TypeError(`webhook event field 'id' must be a UUID (got ${JSON.stringify(id)})`);
  }

  const type = record['type'];
  if (typeof type !== 'string' || !(EVENT_NAMES as readonly string[]).includes(type)) {
    throw new TypeError(`webhook event field 'type' must be a v1 catalog event name`);
  }

  const specversion = record['specversion'];
  if (specversion !== '1.0') {
    throw new TypeError(`webhook event field 'specversion' must be '1.0'`);
  }

  requireStringField(record, 'source');
  requireStringField(record, 'subject');
  requireStringField(record, 'occurred_at');

  const data = record['data'];
  if (typeof data !== 'object' || data === null || Array.isArray(data)) {
    throw new TypeError(`webhook event field 'data' must be an object`);
  }

  return value as WebhookEvent;
}
