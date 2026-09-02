/**
 * Types projected from `contracts/openapi/v1/webhooks.yaml` (the endpoint
 * registration API). The outbound delivery contract (CloudEvents envelope +
 * payload unions + HMAC signature verification) is typed in
 * `src/events.ts` and `src/webhook-signature.ts`.
 *
 * Endpoints are registered per API key. Deliveries are POST requests with a
 * CloudEvents 1.0-aligned JSON envelope, TLS required, signed with
 * `X-SharkPay-Signature: t=<unix>,v1=<hmac-sha256(t + '.' + body, secret)>`
 * (timestamp window ±5 minutes, replay cache 10 minutes). Retries use
 * exponential backoff 1m → 1h, at most 8 attempts. Required scope:
 * `webhooks:manage`.
 */

import type { Page } from './common.js';

/** Webhook endpoint id (`wh_...`). */
export type WebhookEndpointId = string;

/** Pattern for `wh_` ids (webhooks.yaml `WebhookEndpointId` / `WebhookEndpoint.id`). */
export const WEBHOOK_ENDPOINT_ID_PATTERN = /^wh_[0-9A-Za-z]{20,}$/;

/** Type guard for {@link WebhookEndpointId}. */
export function isWebhookEndpointId(value: unknown): value is WebhookEndpointId {
  return typeof value === 'string' && WEBHOOK_ENDPOINT_ID_PATTERN.test(value);
}

/** Risk case id (`case_...`) — appears in `risk.case.opened` event payloads. */
export type CaseId = string;

/** Pattern for `case_` ids (webhooks.yaml `RiskCaseEventData.case_id`). */
export const CASE_ID_PATTERN = /^case_[0-9A-Za-z]{20,}$/;

/** Type guard for {@link CaseId}. */
export function isCaseId(value: unknown): value is CaseId {
  return typeof value === 'string' && CASE_ID_PATTERN.test(value);
}

/**
 * Webhook event catalog (docs/API-CONTRACTS.md §4). The Kafka/CloudEvents
 * counterparts use the versioned topic names (e.g.
 * `payments.payment.succeeded.v1`) — see contracts/events/events.md.
 */
export type EventName =
  | 'payment.created'
  | 'payment.pending_provider'
  | 'payment.succeeded'
  | 'payment.failed'
  | 'payment.expired'
  | 'payment.reversed'
  | 'payout.created'
  | 'payout.processing'
  | 'payout.sent'
  | 'payout.succeeded'
  | 'payout.failed'
  | 'payout.returned'
  | 'transfer.succeeded'
  | 'fx.quote.locked'
  | 'fx.conversion.executed'
  | 'wallet.balance.changed'
  | 'risk.case.opened';

/** Runtime list of {@link EventName} values (the v1 catalog). */
export const EVENT_NAMES: readonly EventName[] = [
  'payment.created',
  'payment.pending_provider',
  'payment.succeeded',
  'payment.failed',
  'payment.expired',
  'payment.reversed',
  'payout.created',
  'payout.processing',
  'payout.sent',
  'payout.succeeded',
  'payout.failed',
  'payout.returned',
  'transfer.succeeded',
  'fx.quote.locked',
  'fx.conversion.executed',
  'wallet.balance.changed',
  'risk.case.opened',
];

/** Type guard for {@link EventName}. */
export function isEventName(value: unknown): value is EventName {
  return typeof value === 'string' && (EVENT_NAMES as readonly string[]).includes(value);
}

/** Delivery state of the endpoint (dead = retries exhausted). */
export type WebhookEndpointState = 'active' | 'dead';

/** Runtime list of {@link WebhookEndpointState} values. */
export const WEBHOOK_ENDPOINT_STATES: readonly WebhookEndpointState[] = ['active', 'dead'];

/** Type guard for {@link WebhookEndpointState}. */
export function isWebhookEndpointState(value: unknown): value is WebhookEndpointState {
  return (
    typeof value === 'string' &&
    (WEBHOOK_ENDPOINT_STATES as readonly string[]).includes(value)
  );
}

/** A registered webhook endpoint. */
export interface WebhookEndpoint {
  id: WebhookEndpointId;
  /** Delivery URL. TLS is required (`^https://`). */
  url: string;
  /** Subscribed events (min 1, unique). */
  events: EventName[];
  state: WebhookEndpointState;
  /**
   * HMAC secret. Returned in full only in the creation response; redacted
   * elsewhere — treat it as absent on get/list.
   */
  secret?: string;
  created_at: string;
  updated_at?: string;
}

/** Request body for POST /webhook-endpoints (createWebhookEndpoint). */
export interface WebhookEndpointCreateRequest {
  /** Delivery URL, `^https://` (TLS required). */
  url: string;
  /** Events to subscribe to (min 1, unique). */
  events: EventName[];
  /** Shared secret for HMAC-SHA256 delivery signatures (16..256 chars). */
  secret: string;
}

/** Page of webhook endpoints (GET /webhook-endpoints). */
export type WebhookEndpointList = Page<WebhookEndpoint>;

/** Query for GET /webhook-endpoints (listWebhookEndpoints). */
export type ListWebhookEndpointsQuery = {
  limit?: number | undefined;
  cursor?: string | undefined;
};

/**
 * Pattern for the `X-SharkPay-Signature` delivery header:
 * `t=<unix seconds>,v1=<hex hmac-sha256 of (t + '.' + raw body) keyed with
 * the endpoint secret>`. See `verifyWebhookSignature` in
 * `src/webhook-signature.ts`.
 */
export const SIGNATURE_HEADER_PATTERN = /^t=[0-9]+,v1=[0-9a-f]{64}$/;

/** Signature timestamp tolerance: ±5 minutes (webhooks.yaml delivery contract). */
export const SIGNATURE_TOLERANCE_MS = 5 * 60 * 1000;
