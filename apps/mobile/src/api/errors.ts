/**
 * Typed error hierarchy for the SharkPay v1 API — mirrored from
 * packages/ts/sharkpay-client/src/errors.ts (see src/api/types.ts for the
 * vendoring rationale).
 *
 * Every non-2xx response carries the shared error envelope
 * (`{ error: { code, message, request_id, details? } }` — common.yaml
 * `ErrorEnvelope`). The client parses that envelope and maps it onto this
 * class hierarchy:
 *
 * | HTTP | Envelope code             | Thrown class                  |
 * |------|----------------------------|-------------------------------|
 * | 400  | `validation_error`         | {@link ValidationError}       |
 * | 401  | `unauthorized`             | {@link AuthError}             |
 * | 403  | `forbidden`                | {@link AuthError}             |
 * | 404  | `not_found`                | {@link ApiError}              |
 * | 409  | `idempotency_conflict`     | {@link IdempotencyConflictError} |
 * | 409  | `state_conflict` (other)   | {@link ApiError}              |
 * | 422  | `insufficient_funds`, …    | {@link ApiError}              |
 * | 429  | `quota_exceeded`           | {@link RateLimitError}        |
 * | 5xx  | `internal_error`           | {@link ApiError}              |
 *
 * Client-side failures surface as {@link TimeoutError} / {@link NetworkError}
 * with `status: 0`.
 */

import type { ErrorBody } from './types';

/** Construction options shared by every {@link SharkPayError}. */
export interface SharkPayErrorInit {
  /** Machine-readable error code (`^[a-z][a-z0-9_]*$`). */
  code: string;
  /** HTTP status of the response (0 for client-side failures). */
  status: number;
  message: string;
  /** Server request id (`error.request_id` from the envelope). */
  requestId?: string;
  /** Machine-readable context (`error.details` from the envelope). */
  details?: Record<string, unknown>;
  /** Underlying cause (e.g. the original network/parse error). */
  cause?: unknown;
}

/**
 * Base class of every error thrown by the mobile API client. Carries the
 * envelope's `code`, the HTTP `status`, and optionally `request_id` /
 * `details` — everything the UI needs to render an actionable error.
 */
export class SharkPayError extends Error {
  readonly code: string;
  readonly status: number;
  readonly requestId: string | undefined;
  readonly details: Record<string, unknown> | undefined;

  constructor(init: SharkPayErrorInit) {
    super(init.message, init.cause !== undefined ? { cause: init.cause } : undefined);
    this.name = new.target.name;
    this.code = init.code;
    this.status = init.status;
    this.requestId = init.requestId;
    this.details = init.details;
  }

  /** Envelope-faithful single-line description (safe for logs/toasts). */
  toString(): string {
    const request = this.requestId !== undefined ? ` [${this.requestId}]` : '';
    return `${this.name}(${this.status}, ${this.code})${request}: ${this.message}`;
  }
}

/**
 * Generic API error for non-2xx responses that do not map to a more specific
 * class: 404 `not_found`, 409 `state_conflict`, 422 business rejections
 * (`insufficient_funds`, `risk_blocked`, `kyc_required`, …), and unparseable
 * bodies (`code: 'invalid_response'`).
 */
export class ApiError extends SharkPayError {}

/** 400 malformed request — `validation_error` (missing fields, bad formats). */
export class ValidationError extends SharkPayError {}

/** 401/403 — missing/invalid/expired token, or scopes deny the operation. */
export class AuthError extends SharkPayError {}

/** 429 quota exceeded. Not auto-retried by the client. */
export class RateLimitError extends SharkPayError {
  /** Parsed `Retry-After` hint in milliseconds, when the server sent one. */
  readonly retryAfterMs: number | undefined;

  constructor(init: SharkPayErrorInit & { retryAfterMs?: number }) {
    super(init);
    this.retryAfterMs = init.retryAfterMs;
  }
}

/**
 * 409 `idempotency_conflict` — an `Idempotency-Key` was reused with a
 * different request payload. Generate a fresh key (or reuse the *same*
 * payload) and retry.
 */
export class IdempotencyConflictError extends SharkPayError {}

/** Client-side: the request exceeded its timeout and was aborted. `status: 0`. Not retried. */
export class TimeoutError extends SharkPayError {}

/** Client-side: the fetch failed before a response arrived. `status: 0`. Retried like 5xx. */
export class NetworkError extends SharkPayError {}

/**
 * Parse the shared error envelope out of a parsed JSON body.
 * Returns `null` when the body is not a well-formed `ErrorEnvelope`
 * (the caller falls back to a status-derived code/message).
 */
export function parseErrorEnvelope(body: unknown): ErrorBody | null {
  if (typeof body !== 'object' || body === null || Array.isArray(body)) {
    return null;
  }
  const envelope = (body as Record<string, unknown>)['error'];
  if (typeof envelope !== 'object' || envelope === null || Array.isArray(envelope)) {
    return null;
  }
  const record = envelope as Record<string, unknown>;
  const code = record['code'];
  const message = record['message'];
  const requestId = record['request_id'];
  if (typeof code !== 'string' || typeof message !== 'string' || typeof requestId !== 'string') {
    return null;
  }
  const details = record['details'];
  return {
    code,
    message,
    request_id: requestId,
    ...(typeof details === 'object' && details !== null && !Array.isArray(details)
      ? { details: details as Record<string, unknown> }
      : {}),
  };
}

/** Context for {@link toSharkPayError}. */
export interface ApiErrorContext {
  /** HTTP status of the response. */
  status: number;
  /** Parsed JSON body (may be anything, including unparseable-garbage `null`). */
  body: unknown;
  /** `Retry-After` hint in milliseconds, when present (429). */
  retryAfterMs?: number | undefined;
  /** `X-Request-Id` response header, used when the envelope lacks `request_id`. */
  headerRequestId?: string | undefined;
}

function fallbackCode(status: number): string {
  if (status === 400) return 'validation_error';
  if (status === 401) return 'unauthorized';
  if (status === 403) return 'forbidden';
  if (status === 404) return 'not_found';
  if (status === 409) return 'state_conflict';
  if (status === 422) return 'request_rejected';
  if (status === 429) return 'quota_exceeded';
  if (status >= 500) return 'internal_error';
  return 'http_error';
}

function fallbackMessage(status: number, body: unknown): string {
  const parsed = parseErrorEnvelope(body);
  if (parsed !== null && parsed.message.length > 0) return parsed.message;
  return `SharkPay API responded with status ${status} and ${
    body === null ? 'an unparseable body' : 'no usable error envelope'
  }.`;
}

/**
 * Map an error response onto the typed error hierarchy. Envelope values
 * (code/message/request_id/details) win; status-derived fallbacks are used
 * when the body is missing or unparseable.
 */
export function toSharkPayError(context: ApiErrorContext): SharkPayError {
  const { status, body } = context;
  const envelope = parseErrorEnvelope(body);
  const code = envelope !== null ? envelope.code : fallbackCode(status);
  const message = fallbackMessage(status, body);
  const requestId = envelope !== null ? envelope.request_id : context.headerRequestId;
  const details = envelope !== null ? envelope.details : undefined;

  const init: SharkPayErrorInit = {
    code,
    status,
    message,
    ...(requestId !== undefined ? { requestId } : {}),
    ...(details !== undefined ? { details } : {}),
  };

  switch (status) {
    case 400:
      return new ValidationError(init);
    case 401:
    case 403:
      return new AuthError(init);
    case 429:
      return new RateLimitError({
        ...init,
        ...(context.retryAfterMs !== undefined ? { retryAfterMs: context.retryAfterMs } : {}),
      });
    default:
      if (status === 409 && code === 'idempotency_conflict') {
        return new IdempotencyConflictError(init);
      }
      return new ApiError(init);
  }
}
