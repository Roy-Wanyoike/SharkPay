/**
 * `SharkPayApiClient` — the typed fetch wrapper for the SharkPay v1 REST API
 * used by the mobile wallet.
 *
 * Shape mirrored from packages/ts/sharkpay-client/src/client.ts (vendored —
 * see src/api/types.ts) with two mobile-specific behaviours:
 *
 * - **Bearer token from the OIDC session** (Keycloak PKCE via
 *   expo-auth-session; see src/auth). The token is resolved per attempt via
 *   an injected `getToken`, so a mid-flight refresh is picked up on retry.
 * - **401 → refresh → one retry.** On a 401 envelope the client calls the
 *   injected `refreshOnUnauthorized` hook (which persists the new session);
 *   if a usable token comes back the request is replayed ONCE with the SAME
 *   Idempotency-Key. A second 401 (or a failed refresh) surfaces as
 *   {@link AuthError} so the app can route to Login.
 *
 * Beyond that, contract behaviour identical to the web console's client:
 * - `Idempotency-Key` on every mutating POST (auto-generated UUID v4 unless
 *   supplied; REUSED across retries and the 401 replay — that is the whole
 *   point of the idempotency contract).
 * - 5xx responses and transport failures retried with exponential backoff +
 *   jitter (safe because every mutation is idempotency-keyed); 4xx/429 are
 *   never retried.
 * - Non-2xx bodies parsed from the shared error envelope into the
 *   `SharkPayError` hierarchy (src/api/errors.ts).
 * - Per-attempt timeout via `AbortController` → `TimeoutError` (not retried).
 */

import {
  ApiError,
  AuthError,
  NetworkError,
  TimeoutError,
  toSharkPayError,
} from './errors';
import { generateIdempotencyKey } from './idempotency';

/** Structural slice of a `Response` the client depends on (mock-friendly). */
export interface FetchResponseLike {
  ok: boolean;
  status: number;
  statusText?: string;
  headers: { get(name: string): string | null };
  text(): Promise<string>;
}

/** A `fetch`-shaped function (the global `fetch` satisfies this). */
export type FetchLike = (
  url: string,
  init: {
    method: string;
    headers: Record<string, string>;
    body?: string | undefined;
    signal?: AbortSignal | undefined;
  },
) => Promise<FetchResponseLike>;

/** Query parameter record (`undefined`/`null`/empty values are dropped). */
export type QueryParams = Record<string, string | number | boolean | undefined | null>;

/** HTTP methods used by the v1 API surface. */
export type HttpMethod = 'GET' | 'POST' | 'DELETE';

/** Per-request options. */
export interface ApiRequestOptions {
  method?: HttpMethod;
  /** Path relative to the API base, always starting with "/" (e.g. "/payments"). */
  path: string;
  /** JSON-serialisable request body. */
  body?: unknown;
  /** Query string params. */
  query?: QueryParams | undefined;
  /**
   * Explicit idempotency key (recommended for money mutations: generate once
   * per logical user intent and reuse across manual retries). A fresh UUID
   * is generated for POSTs when omitted.
   */
  idempotencyKey?: string | undefined;
  /** Extra headers merged over the computed ones. */
  headers?: Record<string, string> | undefined;
}

/** Successful response envelope. */
export interface ApiResult<T> {
  /** Parsed JSON body (`null` for 204/empty bodies). */
  data: T;
  /** HTTP status (2xx). */
  status: number;
  /** `X-Request-Id` response header, when the server sent one. */
  requestId: string | null;
  /** `true` when the server flagged this response as an idempotent replay. */
  idempotentReplay: boolean;
}

/** Constructor options. */
export interface ApiClientOptions {
  /**
   * Base URL INCLUDING the version path, e.g. `http://localhost:8088/v1`
   * (the servers block of every v1 spec). Trailing slash tolerated.
   */
  baseUrl: string;
  /** Bearer token resolved per attempt from the OIDC session. */
  getToken?: () => string | null;
  /**
   * Called once on a 401; must return a usable access token (persisting the
   * refreshed session) or `null`/throw to give up. The failed request is
   * then replayed exactly once with the same Idempotency-Key.
   */
  refreshOnUnauthorized?: () => Promise<string | null>;
  /** Fetch implementation (default: the global `fetch`). */
  fetchImpl?: FetchLike;
  /** Sleep injection point for deterministic retry tests. */
  sleep?: (ms: number) => Promise<void>;
  /** Idempotency key generator (default: UUID v4). */
  idempotencyKeyGenerator?: () => string;
  /** Retries after the first attempt (default 2 → up to 3 attempts). */
  maxRetries?: number;
  /** Base backoff delay in ms (default 300; doubles per attempt + jitter). */
  retryDelayMs?: number;
  /** Per-attempt timeout in ms (default 10_000) → `TimeoutError`. */
  timeoutMs?: number;
}

const IDEMPOTENCY_HEADER = 'Idempotency-Key';
const AUTH_HEADER = 'Authorization';
const DEFAULT_MAX_RETRIES = 2;
const DEFAULT_RETRY_DELAY_MS = 300;
const DEFAULT_TIMEOUT_MS = 10_000;

const defaultSleep = (ms: number): Promise<void> =>
  new Promise((resolve) => {
    setTimeout(resolve, ms);
  });

function normalizeBaseUrl(baseUrl: string): string {
  if (!/^https?:\/\//i.test(baseUrl)) {
    throw new TypeError(
      `baseUrl must be an absolute http(s) URL including the version path, e.g. 'http://localhost:8088/v1' (got ${JSON.stringify(baseUrl)})`,
    );
  }
  return baseUrl.replace(/\/+$/, '');
}

/**
 * URL builder without the `URL` class — Hermes does not guarantee a WHATWG
 * URL global, and plain string building is deterministically testable.
 */
export function buildUrl(baseUrl: string, path: string, query?: QueryParams): string {
  const suffix = path.startsWith('/') ? path : `/${path}`;
  let url = `${baseUrl}${suffix}`;
  const pairs: string[] = [];
  if (query !== undefined) {
    for (const [key, value] of Object.entries(query)) {
      if (value === undefined || value === null || value === '') continue;
      pairs.push(`${encodeURIComponent(key)}=${encodeURIComponent(String(value))}`);
    }
  }
  if (pairs.length > 0) {
    url = `${url}${url.includes('?') ? '&' : '?'}${pairs.join('&')}`;
  }
  return url;
}

/** Exponential backoff + jitter (mirrors the web console's client). */
export function backoffDelay(attempt: number, baseMs: number): number {
  const exponential = baseMs * 2 ** (attempt - 1);
  const jitter = Math.floor(Math.random() * 50);
  return exponential + jitter;
}

function isRetryable(error: unknown): boolean {
  return error instanceof NetworkError || (error instanceof ApiError && error.status >= 500);
}

function safeJsonParse(text: string): unknown {
  try {
    return JSON.parse(text) as unknown;
  } catch {
    return null;
  }
}

/** Parses `Retry-After` (delay-seconds or HTTP-date) into milliseconds. */
export function retryAfterMsFromHeader(
  header: string | null,
  nowMs: number,
): number | undefined {
  if (header === null || header.length === 0) {
    return undefined;
  }
  const seconds = Number(header);
  if (Number.isFinite(seconds)) {
    return Math.max(0, Math.round(seconds * 1000));
  }
  const dateMs = Date.parse(header);
  if (!Number.isNaN(dateMs)) {
    return Math.max(0, dateMs - nowMs);
  }
  return undefined;
}

function headerGet(headers: { get(name: string): string | null }, name: string): string | null {
  return headers.get(name) ?? headers.get(name.toLowerCase());
}

export class SharkPayApiClient {
  private readonly baseUrl: string;
  private readonly getToken: () => string | null;
  private readonly refreshHook: (() => Promise<string | null>) | null;
  private readonly fetchImpl: FetchLike;
  private readonly sleep: (ms: number) => Promise<void>;
  private readonly idempotencyKeyGenerator: () => string;
  private readonly maxRetries: number;
  private readonly retryDelayMs: number;
  private readonly timeoutMs: number;

  constructor(options: ApiClientOptions) {
    this.baseUrl = normalizeBaseUrl(options.baseUrl);
    this.getToken = options.getToken ?? (() => null);
    this.refreshHook = options.refreshOnUnauthorized ?? null;
    this.fetchImpl = options.fetchImpl ?? (globalThis.fetch as unknown as FetchLike);
    this.sleep = options.sleep ?? defaultSleep;
    this.idempotencyKeyGenerator = options.idempotencyKeyGenerator ?? generateIdempotencyKey;
    this.maxRetries = options.maxRetries ?? DEFAULT_MAX_RETRIES;
    this.retryDelayMs = options.retryDelayMs ?? DEFAULT_RETRY_DELAY_MS;
    this.timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
  }

  async request<T>(options: ApiRequestOptions): Promise<ApiResult<T>> {
    const method = options.method ?? 'GET';
    const isMutation = method !== 'GET';
    // Generated once per logical request and reused across network retries
    // AND the 401-refresh replay — that is the whole point of the contract.
    const idempotencyKey =
      options.idempotencyKey !== undefined
        ? options.idempotencyKey
        : isMutation
          ? this.idempotencyKeyGenerator()
          : null;

    try {
      return await this.attemptWithRetries<T>(options, method, idempotencyKey);
    } catch (error) {
      if (
        this.refreshHook === null ||
        !(error instanceof AuthError) ||
        error.status !== 401
      ) {
        throw error;
      }
      // 401: try to refresh the session and replay ONCE with the same key.
      let refreshedToken: string | null;
      try {
        refreshedToken = await this.refreshHook();
      } catch (refreshError) {
        throw new AuthError({
          code: 'unauthorized',
          status: 401,
          message: 'Session expired and the token refresh failed. Please sign in again.',
          cause: refreshError,
        });
      }
      if (typeof refreshedToken !== 'string' || refreshedToken.length === 0) {
        throw error;
      }
      return this.attemptWithRetries<T>(options, method, idempotencyKey);
    }
  }

  private async attemptWithRetries<T>(
    options: ApiRequestOptions,
    method: HttpMethod,
    idempotencyKey: string | null,
  ): Promise<ApiResult<T>> {
    let lastError: unknown = new Error('unreachable');
    for (let attempt = 1; attempt <= this.maxRetries + 1; attempt++) {
      try {
        return await this.attemptOnce<T>(options, method, idempotencyKey);
      } catch (error) {
        lastError = error;
        if (attempt > this.maxRetries || !isRetryable(error)) {
          throw error;
        }
        await this.sleep(backoffDelay(attempt, this.retryDelayMs));
      }
    }
    throw lastError;
  }

  private async attemptOnce<T>(
    options: ApiRequestOptions,
    method: HttpMethod,
    idempotencyKey: string | null,
  ): Promise<ApiResult<T>> {
    const url = buildUrl(this.baseUrl, options.path, options.query);
    const headers: Record<string, string> = {
      Accept: 'application/json',
      ...options.headers,
    };
    if (options.body !== undefined) {
      headers['Content-Type'] = 'application/json';
    }
    const token = this.getToken();
    if (token !== null && token.length > 0) {
      headers[AUTH_HEADER] = `Bearer ${token}`;
    }
    if (idempotencyKey !== null) {
      headers[IDEMPOTENCY_HEADER] = idempotencyKey;
    }

    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    let response: FetchResponseLike;
    try {
      response = await this.fetchImpl(url, {
        method,
        headers,
        body: options.body === undefined ? undefined : JSON.stringify(options.body),
        signal: controller.signal,
      });
    } catch (error) {
      if (controller.signal.aborted) {
        throw new TimeoutError({
          code: 'timeout',
          status: 0,
          message: `Request to ${options.path} exceeded ${this.timeoutMs}ms and was aborted.`,
          cause: error,
        });
      }
      throw new NetworkError({
        code: 'network_error',
        status: 0,
        message: `Request to ${options.path} failed: ${
          error instanceof Error ? error.message : String(error)
        }`,
        cause: error,
      });
    } finally {
      clearTimeout(timer);
    }

    const text = await response.text();
    const body: unknown = text.length === 0 ? null : safeJsonParse(text);

    if (!response.ok) {
      throw toSharkPayError({
        status: response.status,
        body,
        retryAfterMs: retryAfterMsFromHeader(
          headerGet(response.headers, 'Retry-After'),
          Date.now(),
        ),
        headerRequestId: headerGet(response.headers, 'X-Request-Id') ?? undefined,
      });
    }

    return {
      data: body as T,
      status: response.status,
      requestId: headerGet(response.headers, 'X-Request-Id'),
      idempotentReplay: headerGet(response.headers, 'X-Idempotent-Replay') === 'true',
    };
  }

  /** GET returning only the parsed body. */
  get<T>(path: string, query?: QueryParams): Promise<T> {
    return this.request<T>({ method: 'GET', path, query }).then((result) => result.data);
  }

  /**
   * POST with an explicit idempotency key — the form every money mutation in
   * this app uses (key minted once per user intent, reused across retries).
   */
  post<T>(path: string, body: unknown, idempotencyKey?: string): Promise<T> {
    return this.request<T>({ method: 'POST', path, body, idempotencyKey }).then(
      (result) => result.data,
    );
  }

  /** POST with an auto-generated (single-attempt-scope) idempotency key. */
  postAutoKey<T>(path: string, body?: unknown): Promise<T> {
    return this.request<T>({ method: 'POST', path, body }).then((result) => result.data);
  }

  delete<T>(path: string, idempotencyKey?: string): Promise<T> {
    return this.request<T>({ method: 'DELETE', path, idempotencyKey }).then(
      (result) => result.data,
    );
  }
}
