/**
 * Shared types projected from `contracts/openapi/v1/common.yaml`.
 *
 * These are the building blocks every SharkPay /v1 service spec references:
 * the money shape, the error envelope, cursor pagination parameters and the
 * shared id patterns.
 */

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
 * ## int64 safety note
 *
 * `amount_minor` is `int64` on the wire. The client parses response bodies
 * with `JSON.parse`, which yields a JS `number` — exact only within
 * ±(2^53 − 1) = ±9,007,199,254,740,991 minor units (≈ 90 trillion major
 * units at exponent 2 — many orders of magnitude beyond any realistic V1
 * balance). All V1 money fits comfortably in that envelope. If you must
 * handle arbitrary int64 values exactly, use `parseMoneyLossless` (bigint)
 * from `@sharkpay/client/money` on the raw response text.
 */
export interface Money {
  /** Signed minor units (`int64` on the wire; see the int64 safety note). */
  amount_minor: number;
  currency: Currency;
  /** Minor-unit exponent of the currency. Must match the currency table (0..18). */
  exponent: number;
}

/**
 * Positive money input for create requests (amount in minor units plus
 * currency) — common.yaml `AmountIn`. The create requests in the service
 * specs inline their own `amount_minor`/`currency` pair instead; this type is
 * exported for completeness.
 */
export interface AmountIn {
  amount_minor: number;
  currency: Currency;
}

/**
 * Caller-supplied key/value metadata stored with the resource
 * (common.yaml `Metadata`: `additionalProperties: true`, `maxProperties: 20`).
 * The 20-key bound and JSON-value freedom are server-enforced, not typed.
 */
export type Metadata = Record<string, unknown>;

/** Cursor for the next page; null/absent when there are no more results. */
export type NextCursor = string | null;

/**
 * Cursor-paginated page (common.yaml pagination convention: `?limit=` max
 * 100, `?cursor=`). Every list response in the v1 specs has this shape.
 */
export interface Page<T> {
  items: T[];
  next_cursor?: NextCursor;
}

/** Page size bounds for the shared `limit` query parameter (1..100, default 50). */
export const LIMIT_MIN = 1;
export const LIMIT_MAX = 100;
export const LIMIT_DEFAULT = 50;

/**
 * Machine-readable error body inside {@link ErrorEnvelope} (common.yaml
 * `Error`). `code` matches `^[a-z][a-z0-9_]*$`; see {@link CommonErrorCode}
 * for the documented v1 codes — the server may append new codes
 * additively, so `code` stays an open `string`.
 */
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

/** The error codes documented across the v1 specs (non-exhaustive by design). */
export type CommonErrorCode =
  | 'validation_error'
  | 'unauthorized'
  | 'forbidden'
  | 'not_found'
  | 'idempotency_conflict'
  | 'state_conflict'
  | 'insufficient_funds'
  | 'risk_blocked'
  | 'kyc_required'
  | 'quota_exceeded'
  | 'internal_error';

/** Server-assigned request id (`req_...`, pattern `^req_[0-9A-Za-z]+$`). */
export type RequestId = string;

/** Pattern for {@link RequestId} (common.yaml `RequestId` header). */
export const REQUEST_ID_PATTERN = /^req_[0-9A-Za-z]+$/;

/** Type guard for {@link RequestId}. */
export function isRequestId(value: unknown): value is RequestId {
  return typeof value === 'string' && REQUEST_ID_PATTERN.test(value);
}

/**
 * Idempotency key for state-changing POSTs (common.yaml `IdempotencyKey`
 * header: UUID format, 1..128 chars). Scope is `(api key, endpoint, key)`;
 * retries with the same key return the original response with
 * `X-Idempotent-Replay: true`; reuse with a different payload is a 409
 * `idempotency_conflict`. The client auto-generates one (UUID v4 via
 * `crypto.randomUUID`) for every mutation unless you supply your own.
 */
export type IdempotencyKey = string;

/** `Retry-After` semantics: minimum backoff the server asks for (client-side hint). */
export const RATE_LIMIT_RETRY_AFTER_HEADER = 'retry-after';
