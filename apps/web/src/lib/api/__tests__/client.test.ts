import { describe, expect, it, vi } from "vitest";
import { ApiClient } from "@/lib/api/client";
import { ApiError, ApiNetworkError } from "@/lib/api/errors";

type FetchMock = ReturnType<typeof vi.fn>;

function jsonResponse(status: number, body: unknown, headers: Record<string, string> = {}): Response {
  return new Response(JSON.stringify(body), {
    status,
    headers: { "Content-Type": "application/json", ...headers },
  });
}

function makeClient(options: { fetchImpl: FetchMock; accessToken?: string | null }): ApiClient {
  return new ApiClient({
    baseUrl: "http://api.test",
    fetchImpl: options.fetchImpl as unknown as typeof fetch,
    sleep: async () => undefined,
    maxRetries: 2,
    retryDelayMs: 1,
    timeoutMs: 30_000,
    accessToken: options.accessToken,
  });
}

interface CapturedRequest {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: string | undefined;
}

function captureCalls(fetchImpl: FetchMock): CapturedRequest[] {
  return fetchImpl.mock.calls.map((call) => {
    const [url, init] = call as [string, RequestInit];
    return {
      url,
      method: init.method as string,
      headers: (init.headers ?? {}) as Record<string, string>,
      body: init.body as string | undefined,
    };
  });
}

describe("ApiClient basics", () => {
  it("performs a GET with query params (undefined skipped) and returns parsed JSON", async () => {
    const fetchImpl = vi.fn(async () =>
      jsonResponse(200, { items: [{ id: "pay_1" }], next_cursor: null }, { "X-Request-Id": "req_9" }),
    );
    const client = makeClient({ fetchImpl });

    const data = await client.get<{ items: Array<{ id: string }>; next_cursor: null }>("/payments", {
      state: "PENDING_PROVIDER",
      cursor: undefined,
      limit: 25,
    });

    expect(data.items).toEqual([{ id: "pay_1" }]);
    const [request] = captureCalls(fetchImpl);
    expect(request.method).toBe("GET");
    expect(request.url).toBe("http://api.test/payments?state=PENDING_PROVIDER&limit=25");
    expect(request.headers.Accept).toBe("application/json");
    expect(request.headers.Authorization).toBeUndefined();
  });

  it("injects the bearer token when a session token is present", async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(200, { ok: true }));
    const client = makeClient({ fetchImpl, accessToken: "token-123" });
    await client.get("/wallets");
    expect(captureCalls(fetchImpl)[0].headers.Authorization).toBe("Bearer token-123");
  });

  it("captures X-Request-Id and X-Idempotent-Replay on the raw result", async () => {
    const fetchImpl = vi.fn(async () =>
      jsonResponse(
        200,
        { id: "pay_1" },
        { "X-Request-Id": "req_42", "X-Idempotent-Replay": "true" },
      ),
    );
    const client = makeClient({ fetchImpl });
    const result = await client.request<{ id: string }>({ method: "GET", path: "/payments/x" });
    expect(result.requestId).toBe("req_42");
    expect(result.idempotentReplay).toBe(true);
    expect(result.data.id).toBe("pay_1");
  });
});

describe("idempotency", () => {
  it("sends an Idempotency-Key on POST and serialises the body as JSON", async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(201, { id: "pay_2" }));
    const client = makeClient({ fetchImpl });
    await client.post("/payments", { amount_minor: 1500, currency: "KES" });

    const [request] = captureCalls(fetchImpl);
    expect(request.headers["Content-Type"]).toBe("application/json");
    expect(request.headers["Idempotency-Key"]).toMatch(/^[0-9a-f-]{36}$/);
    expect(request.body).toBe(JSON.stringify({ amount_minor: 1500, currency: "KES" }));
  });

  it("does not send an Idempotency-Key on GET", async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(200, { items: [] }));
    const client = makeClient({ fetchImpl });
    await client.get("/payments");
    expect(captureCalls(fetchImpl)[0].headers["Idempotency-Key"]).toBeUndefined();
  });

  it("reuses the SAME key across retries of one logical request", async () => {
    const fetchImpl = vi
      .fn<() => Promise<Response>>()
      .mockResolvedValueOnce(jsonResponse(500, { error: { code: "internal_error", message: "x", request_id: "r" } }))
      .mockResolvedValueOnce(jsonResponse(201, { id: "pay_3" }));
    const client = makeClient({ fetchImpl });

    const payment = await client.post<{ id: string }>("/payments", { amount_minor: 1, currency: "KES" });
    expect(payment.id).toBe("pay_3");

    const calls = captureCalls(fetchImpl);
    expect(calls).toHaveLength(2);
    expect(calls[0].headers["Idempotency-Key"]).toBe(calls[1].headers["Idempotency-Key"]);
  });

  it("generates a fresh key per logical request", async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(201, { id: "x" }));
    const client = makeClient({ fetchImpl });
    await client.post("/payments", { amount_minor: 1 });
    await client.post("/payments", { amount_minor: 2 });
    const calls = captureCalls(fetchImpl);
    expect(calls[0].headers["Idempotency-Key"]).not.toBe(calls[1].headers["Idempotency-Key"]);
  });

  it("can opt out of idempotency explicitly", async () => {
    const fetchImpl = vi.fn(async () => jsonResponse(200, {}));
    const client = makeClient({ fetchImpl });
    await client.postNoIdempotency("/payments");
    expect(captureCalls(fetchImpl)[0].headers["Idempotency-Key"]).toBeUndefined();
  });
});

describe("error envelope handling", () => {
  it("throws a typed ApiError with envelope fields on 4xx", async () => {
    const fetchImpl = vi.fn(async () =>
      jsonResponse(404, {
        error: { code: "not_found", message: "Payment not found.", request_id: "req_7" },
      }),
    );
    const client = makeClient({ fetchImpl });

    const error = await client.get("/payments/pay_missing").catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(ApiError);
    const apiError = error as ApiError;
    expect(apiError.status).toBe(404);
    expect(apiError.code).toBe("not_found");
    expect(apiError.requestId).toBe("req_7");
  });

  it("handles non-JSON error bodies without throwing during parse", async () => {
    const fetchImpl = vi.fn(async () => new Response("<html>proxy error</html>", { status: 502 }));
    const client = makeClient({ fetchImpl });

    await expect(client.get("/payments")).rejects.toSatisfy((error: unknown) => {
      return error instanceof ApiError && error.status === 502 && error.code === "http_error";
    });
  });

  it("treats 204 responses as undefined data", async () => {
    const fetchImpl = vi.fn(async () => new Response(null, { status: 204 }));
    const client = makeClient({ fetchImpl });
    await expect(client.get("/nothing")).resolves.toBeUndefined();
  });
});

describe("retry/backoff", () => {
  it("retries 5xx responses and succeeds on a later attempt", async () => {
    const fetchImpl = vi
      .fn<() => Promise<Response>>()
      .mockResolvedValueOnce(jsonResponse(503, {}))
      .mockResolvedValueOnce(jsonResponse(500, { error: { code: "internal_error", message: "x", request_id: "r" } }))
      .mockResolvedValueOnce(jsonResponse(200, { items: [], next_cursor: null }));
    const client = makeClient({ fetchImpl });

    const data = await client.get<{ items: unknown[] }>("/payments");
    expect(data.items).toEqual([]);
    expect(fetchImpl).toHaveBeenCalledTimes(3);
  });

  it("gives up after maxRetries and surfaces the last ApiError", async () => {
    const fetchImpl = vi.fn(async () =>
      jsonResponse(500, { error: { code: "internal_error", message: "down", request_id: "r" } }),
    );
    const client = makeClient({ fetchImpl });

    const error = await client.get("/payments").catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).status).toBe(500);
    expect(fetchImpl).toHaveBeenCalledTimes(3); // 1 attempt + 2 retries
  });

  it("retries transport (network) failures", async () => {
    const fetchImpl = vi
      .fn<() => Promise<Response>>()
      .mockRejectedValueOnce(new TypeError("fetch failed"))
      .mockResolvedValueOnce(jsonResponse(200, { ok: true }));
    const client = makeClient({ fetchImpl });

    await expect(client.get("/payments")).resolves.toEqual({ ok: true });
    expect(fetchImpl).toHaveBeenCalledTimes(2);
  });

  it("wraps persistent transport failures in ApiNetworkError", async () => {
    const fetchImpl = vi.fn(async () => {
      throw new TypeError("ECONNREFUSED");
    });
    const client = makeClient({ fetchImpl });

    const error = await client.get("/payments").catch((caught: unknown) => caught);
    expect(error).toBeInstanceOf(ApiNetworkError);
    expect(fetchImpl).toHaveBeenCalledTimes(3);
  });

  it("never retries 4xx responses", async () => {
    const fetchImpl = vi.fn(async () =>
      jsonResponse(422, { error: { code: "risk_blocked", message: "blocked", request_id: "r" } }),
    );
    const client = makeClient({ fetchImpl });

    await expect(client.get("/payments")).rejects.toBeInstanceOf(ApiError);
    expect(fetchImpl).toHaveBeenCalledTimes(1);
  });
});
