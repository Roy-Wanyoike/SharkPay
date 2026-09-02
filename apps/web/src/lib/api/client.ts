import { ApiError, ApiNetworkError, isRetryableError } from "@/lib/api/errors";
import { generateIdempotencyKey } from "@/lib/api/idempotency";
import { getApiBaseUrl } from "@/lib/env";

/**
 * Typed fetch wrapper for the SharkPay /v1 API surface.
 *
 * Behaviour pinned by contracts/openapi/v1/common.yaml:
 * - Authorization: Bearer <token> (injected from the OIDC session).
 * - Idempotency-Key is generated for every mutating request (POST by
 *   default, opt-in per call) and — critically — REUSED across retries of
 *   the same logical request.
 * - Errors are parsed into the typed envelope { error: { code, message,
 *   request_id, details } }.
 * - Transport errors and 5xx responses are retried with exponential
 *   backoff + jitter (safe because every mutation is idempotent-keyed);
 *   4xx responses are never retried.
 */

export type QueryParams = Record<string, string | number | boolean | undefined | null>;

export interface RequestConfig {
  method: "GET" | "POST" | "PUT" | "PATCH" | "DELETE";
  /** Path relative to the API base, always starting with "/" (e.g. "/payments"). */
  path: string;
  /** JSON-serialisable request body. */
  body?: unknown;
  /** Query string params; undefined/null/"" entries are skipped. */
  query?: QueryParams;
  /** Extra headers merged over the computed ones. */
  headers?: Record<string, string>;
  /** Generate an Idempotency-Key (default: POST requests). */
  idempotent?: boolean;
  /** Abort signal for the final attempt only (retries have their own timeout). */
  signal?: AbortSignal;
}

export interface ApiResult<T> {
  data: T;
  /** X-Request-Id response header when present. */
  requestId: string | null;
  /** True when the server replayed an earlier idempotent response. */
  idempotentReplay: boolean;
}

export interface ApiClientOptions {
  baseUrl?: string;
  /** Bearer token injected as Authorization when non-null. */
  accessToken?: string | null;
  /** Injectable fetch for tests. */
  fetchImpl?: typeof fetch;
  /** Injectable sleep for deterministic backoff tests. */
  sleep?: (ms: number) => Promise<void>;
  /** Retries after the first attempt (default 2 → up to 3 attempts). */
  maxRetries?: number;
  /** Base backoff delay in ms (default 300, doubled per attempt + jitter). */
  retryDelayMs?: number;
  /** Per-attempt timeout in ms (default 10_000). */
  timeoutMs?: number;
}

const IDEMPOTENT_HEADER = "Idempotency-Key";
const MUTATING_METHODS = new Set<RequestConfig["method"]>(["POST", "PUT", "PATCH", "DELETE"]);

function buildUrl(baseUrl: string, path: string, query?: QueryParams): string {
  const url = new URL(`${baseUrl}${path.startsWith("/") ? path : `/${path}`}`);
  if (query) {
    for (const [key, value] of Object.entries(query)) {
      if (value === undefined || value === null || value === "") continue;
      url.searchParams.set(key, String(value));
    }
  }
  return url.toString();
}

function backoffDelay(attempt: number, baseMs: number): number {
  const exponential = baseMs * 2 ** (attempt - 1);
  const jitter = Math.floor(Math.random() * 50);
  return exponential + jitter;
}

export class ApiClient {
  private readonly baseUrl: string;
  private readonly accessToken: string | null;
  private readonly fetchImpl: typeof fetch;
  private readonly sleep: (ms: number) => Promise<void>;
  private readonly maxRetries: number;
  private readonly retryDelayMs: number;
  private readonly timeoutMs: number;

  constructor(options: ApiClientOptions = {}) {
    this.baseUrl = options.baseUrl ?? getApiBaseUrl();
    this.accessToken = options.accessToken ?? null;
    this.fetchImpl = options.fetchImpl ?? fetch;
    this.sleep = options.sleep ?? ((ms: number) => new Promise((resolve) => setTimeout(resolve, ms)));
    this.maxRetries = options.maxRetries ?? 2;
    this.retryDelayMs = options.retryDelayMs ?? 300;
    this.timeoutMs = options.timeoutMs ?? 10_000;
  }

  async request<T>(config: RequestConfig): Promise<ApiResult<T>> {
    const url = buildUrl(this.baseUrl, config.path, config.query);
    const isMutation = config.idempotent ?? MUTATING_METHODS.has(config.method);
    // Generated once and reused across retries — that is the whole point of
    // the idempotency contract.
    const idempotencyKey = isMutation ? generateIdempotencyKey() : null;

    let lastError: unknown = new Error("unreachable");
    for (let attempt = 1; attempt <= this.maxRetries + 1; attempt++) {
      try {
        return await this.attemptRequest<T>(url, config, idempotencyKey);
      } catch (error) {
        lastError = error;
        if (attempt > this.maxRetries || !isRetryableError(error)) {
          throw error;
        }
        await this.sleep(backoffDelay(attempt, this.retryDelayMs));
      }
    }
    throw lastError;
  }

  private async attemptRequest<T>(
    url: string,
    config: RequestConfig,
    idempotencyKey: string | null,
  ): Promise<ApiResult<T>> {
    const headers: Record<string, string> = {
      Accept: "application/json",
      ...config.headers,
    };
    if (config.body !== undefined) {
      headers["Content-Type"] = "application/json";
    }
    if (this.accessToken) {
      headers.Authorization = `Bearer ${this.accessToken}`;
    }
    if (idempotencyKey) {
      headers[IDEMPOTENT_HEADER] = idempotencyKey;
    }

    const timeoutSignal = AbortSignal.timeout(this.timeoutMs);
    const signal = config.signal
      ? AbortSignal.any([config.signal, timeoutSignal])
      : timeoutSignal;

    let response: Response;
    try {
      response = await this.fetchImpl(url, {
        method: config.method,
        headers,
        body: config.body === undefined ? undefined : JSON.stringify(config.body),
        signal,
      });
    } catch (error) {
      throw new ApiNetworkError(
        `Request to ${config.path} failed: ${error instanceof Error ? error.message : String(error)}`,
        { cause: error },
      );
    }

    if (!response.ok) {
      const body: unknown = await response
        .json()
        .catch(() => null as unknown as null);
      throw ApiError.fromResponse(response.status, body);
    }

    if (response.status === 204) {
      return { data: undefined as T, requestId: response.headers.get("X-Request-Id"), idempotentReplay: false };
    }

    const text = await response.text();
    const data = (text.length === 0 ? undefined : (JSON.parse(text) as unknown)) as T;
    const replayHeader = response.headers.get("X-Idempotent-Replay");
    return {
      data,
      requestId: response.headers.get("X-Request-Id"),
      idempotentReplay: replayHeader === "true",
    };
  }

  get<T>(path: string, query?: QueryParams): Promise<T> {
    return this.request<T>({ method: "GET", path, query }).then((result) => result.data);
  }

  post<T>(path: string, body?: unknown, options: Pick<RequestConfig, "query" | "headers"> = {}): Promise<T> {
    return this.request<T>({ method: "POST", path, body, ...options }).then((result) => result.data);
  }

  /** POST that opts OUT of idempotency (rare — e.g. expiring re-issues). */
  postNoIdempotency<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>({ method: "POST", path, body, idempotent: false }).then(
      (result) => result.data,
    );
  }
}
