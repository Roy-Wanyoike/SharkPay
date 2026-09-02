/**
 * Shared OpenAPI 3.1 shapes — ported faithfully from
 * contracts/openapi/v1/common.yaml. The service-specific schemas live next to
 * their SDK module (payments.ts, wallets.ts, ...).
 *
 * Conventions pinned by common.yaml:
 * - Money is always { amount_minor, currency, exponent } — integer minor
 *   units, never floats.
 * - Pagination is cursor-based (?limit= max 100, ?cursor=), pages are
 *   { items, next_cursor } with next_cursor null/absent at the end.
 * - Every error is the single envelope { error: { code, message,
 *   request_id, details? } }.
 */

/** Supported currencies at V1 (fiat exponent 2, stablecoins 6). */
export type Currency = "KES" | "USD" | "EUR" | "GBP" | "USDC" | "USDT";

/** Integer-only money (common.yaml Money). */
export interface Money {
  /** Signed minor units (int64 on the wire). */
  amount_minor: number;
  currency: Currency;
  /** Minor-unit exponent of the currency. Must match the currency table. */
  exponent: number;
}

/** Exchange rate (fx.yaml Rate): quote minor units per one base unit. */
export interface Rate {
  value_minor: number;
  exponent: number;
  base_currency: Currency;
  quote_currency: Currency;
}

/** Caller-supplied key/value metadata stored with a resource (max 20 keys). */
export type Metadata = Record<string, unknown>;

/** Cursor for the next page; null/absent when there are no more results. */
export type NextCursor = string | null;

/** Cursor-paginated page (common.yaml list wrappers). */
export interface Page<T> {
  items: T[];
  next_cursor?: NextCursor;
}

/** The single error envelope used by every endpoint (common.yaml Error). */
export interface ErrorEnvelope {
  error: {
    /** Machine-readable code, e.g. validation_error, insufficient_funds. */
    code: string;
    /** Human-readable explanation. */
    message: string;
    /** Correlates with the X-Request-Id response header and server logs. */
    request_id: string;
    /** Optional machine-readable context (e.g. available_minor). */
    details?: Record<string, unknown>;
  };
}

/** Common query parameters for list endpoints. */
export interface PageParams {
  /** Page size 1..100, defaults to 50 server-side. */
  limit?: number;
  /** Opaque cursor from a previous page's next_cursor. */
  cursor?: string;
}
