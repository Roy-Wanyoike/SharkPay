import type { ErrorEnvelope } from "@/lib/api/sdk/types";

/**
 * Typed API error classes for the SharkPay error envelope
 * (contracts/openapi/v1/common.yaml §ErrorEnvelope).
 */

const FALLBACK_CODE_BY_STATUS: Record<number, string> = {
  400: "validation_error",
  401: "unauthorized",
  403: "forbidden",
  404: "not_found",
  409: "state_conflict",
  422: "business_rule_rejection",
  429: "quota_exceeded",
  500: "internal_error",
};

/** Raised for any non-2xx response whose body is (or should be) an envelope. */
export class ApiError extends Error {
  readonly status: number;
  readonly code: string;
  readonly requestId: string;
  readonly details?: Record<string, unknown>;

  constructor(
    status: number,
    shape: { code: string; message: string; requestId: string; details?: Record<string, unknown> },
  ) {
    // .message stays faithful to the contract envelope (machine-readable
    // separation: status/code are fields); the decorated form is toString().
    super(shape.message);
    this.name = "ApiError";
    this.status = status;
    this.code = shape.code;
    this.requestId = shape.requestId;
    this.details = shape.details;
  }

  override toString(): string {
    return `[${this.status} ${this.code}] ${this.message}`;
  }

  /** 401 — the session/bearer token is missing or invalid. */
  get isUnauthorized(): boolean {
    return this.status === 401;
  }

  /** 403 — the key's scopes (or agent policy) deny the operation. */
  get isForbidden(): boolean {
    return this.status === 403;
  }

  /** 404 — a referenced entity does not exist. */
  get isNotFound(): boolean {
    return this.status === 404;
  }

  /** 429 — quota exceeded; retrying immediately is counter-productive. */
  get isQuotaExceeded(): boolean {
    return this.status === 429;
  }

  /**
   * Parses an error envelope body. Accepts non-envelope bodies (proxies,
   * HTML error pages) by falling back to a code derived from the status.
   */
  static fromResponse(status: number, body: unknown): ApiError {
    const envelope = body as Partial<ErrorEnvelope> | null;
    const error = envelope && typeof envelope === "object" ? envelope.error : undefined;
    if (
      error &&
      typeof error.code === "string" &&
      typeof error.message === "string"
    ) {
      return new ApiError(status, {
        code: error.code,
        message: error.message,
        requestId: typeof error.request_id === "string" ? error.request_id : "",
        details:
          error.details && typeof error.details === "object"
            ? (error.details as Record<string, unknown>)
            : undefined,
      });
    }
    return new ApiError(status, {
      code: FALLBACK_CODE_BY_STATUS[status] ?? "http_error",
      message: `Request failed with status ${status}.`,
      requestId: "",
    });
  }
}

/** Raised for transport failures (DNS, refused connections, timeouts). */
export class ApiNetworkError extends Error {
  constructor(message: string, options: { cause?: unknown } = {}) {
    super(message, options);
    this.name = "ApiNetworkError";
  }
}

export function isRetryableError(error: unknown): boolean {
  if (error instanceof ApiNetworkError) return true;
  if (error instanceof ApiError) return error.status >= 500;
  return false;
}
