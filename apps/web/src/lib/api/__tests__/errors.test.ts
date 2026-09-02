import { describe, expect, it } from "vitest";
import { generateIdempotencyKey } from "@/lib/api/idempotency";
import { ApiError, ApiNetworkError, isRetryableError } from "@/lib/api/errors";

describe("generateIdempotencyKey", () => {
  it("generates UUID-shaped keys", () => {
    const key = generateIdempotencyKey();
    expect(key).toMatch(
      /^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/,
    );
  });

  it("generates unique keys per call", () => {
    const keys = new Set(Array.from({ length: 200 }, () => generateIdempotencyKey()));
    expect(keys.size).toBe(200);
  });
});

describe("ApiError.fromResponse", () => {
  it("parses the contract error envelope", () => {
    const error = ApiError.fromResponse(404, {
      error: {
        code: "not_found",
        message: "Payment pay_01HZ not found.",
        request_id: "req_01HZXK2P9Q",
      },
    });
    expect(error).toBeInstanceOf(ApiError);
    expect(error.status).toBe(404);
    expect(error.code).toBe("not_found");
    expect(error.message).toBe("Payment pay_01HZ not found.");
    expect(error.requestId).toBe("req_01HZXK2P9Q");
    expect(error.isNotFound).toBe(true);
  });

  it("carries the details object", () => {
    const error = ApiError.fromResponse(422, {
      error: {
        code: "insufficient_funds",
        message: "Wallet balance after holds is 1200, requested 5000.",
        request_id: "req_1",
        details: { available_minor: 1200, requested_minor: 5000 },
      },
    });
    expect(error.details).toEqual({ available_minor: 1200, requested_minor: 5000 });
  });

  it("falls back to a status-derived code for non-envelope bodies", () => {
    const error = ApiError.fromResponse(502, "Bad Gateway");
    expect(error.code).toBe("http_error");
    expect(error.status).toBe(502);
  });

  it("falls back for envelope-shaped codes per common status map", () => {
    expect(ApiError.fromResponse(400, null).code).toBe("validation_error");
    expect(ApiError.fromResponse(401, null).code).toBe("unauthorized");
    expect(ApiError.fromResponse(403, null).code).toBe("forbidden");
    expect(ApiError.fromResponse(409, null).code).toBe("state_conflict");
    expect(ApiError.fromResponse(429, null).code).toBe("quota_exceeded");
  });

  it("keeps an empty request_id when the body omits it", () => {
    const error = ApiError.fromResponse(500, {
      error: { code: "internal_error", message: "boom" },
    });
    expect(error.requestId).toBe("");
  });
});

describe("error classification", () => {
  it("exposes auth/forbidden/quota predicates", () => {
    expect(new ApiError(401, { code: "unauthorized", message: "x", requestId: "" }).isUnauthorized).toBe(true);
    expect(new ApiError(403, { code: "forbidden", message: "x", requestId: "" }).isForbidden).toBe(true);
    expect(new ApiError(429, { code: "quota_exceeded", message: "x", requestId: "" }).isQuotaExceeded).toBe(true);
    expect(new ApiError(404, { code: "not_found", message: "x", requestId: "" }).isNotFound).toBe(true);
  });

  it("marks 5xx and transport errors retryable, 4xx not", () => {
    expect(isRetryableError(new ApiError(500, { code: "internal_error", message: "x", requestId: "" }))).toBe(true);
    expect(isRetryableError(new ApiError(503, { code: "http_error", message: "x", requestId: "" }))).toBe(true);
    expect(isRetryableError(new ApiError(409, { code: "state_conflict", message: "x", requestId: "" }))).toBe(false);
    expect(isRetryableError(new ApiNetworkError("refused"))).toBe(true);
  });
});
