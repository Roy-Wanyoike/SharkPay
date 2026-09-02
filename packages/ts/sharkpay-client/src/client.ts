/**
 * `SharkPayClient` — a typed fetch wrapper for the SharkPay v1 REST API,
 * hand-crafted from contracts/openapi/v1/{payments,payouts,transfers,
 * wallets,fx,webhooks}.yaml.
 *
 * Behaviours beyond a dumb fetch wrapper, all derived from the contracts:
 *
 * - **Auth**: `Authorization: Bearer <api key>` (common.yaml `BearerAuth`).
 *   Pass `apiKey` (or `bearerToken` as an alias for future OAuth flows —
 *   both produce the same `Bearer` header).
 * - **Idempotency**: every mutating POST automatically carries an
 *   `Idempotency-Key` header (UUID v4 via `crypto.randomUUID`) unless you
 *   supply your own — required on all state-changing POSTs. Retries reuse
 *   the same key; replayed responses are flagged server-side with
 *   `X-Idempotent-Replay: true`.
 * - **Retries**: 5xx responses and network failures are retried with
 *   bounded exponential backoff + jitter (default 3 retries, 200ms base,
 *   5s cap; opt out with `retry: false`). 429 is *not* auto-retried — it
 *   surfaces immediately as `RateLimitError` with the parsed `Retry-After`.
 * - **Timeouts**: every request is aborted after `timeoutMs` (default
 *   10s) and surfaces as `TimeoutError` (not retried).
 * - **Typed errors**: non-2xx responses are parsed from the shared error
 *   envelope into the `SharkPayError` hierarchy (see `src/errors.ts`).
 */

import type {
  Conversion,
  ConversionCreateRequest,
  Quote,
  QuoteCreateRequest,
} from './types/fx.js';
import type {
  ListPaymentsQuery,
  Payment,
  PaymentCreateRequest,
  PaymentId,
  PaymentList,
} from './types/payments.js';
import type {
  Payout,
  PayoutCreateRequest,
  PayoutId,
} from './types/payouts.js';
import type {
  ListWalletsQuery,
  StatementList,
  StatementQuery,
  Wallet,
  WalletId,
  WalletList,
} from './types/wallets.js';
import type {
  ListWebhookEndpointsQuery,
  WebhookEndpoint,
  WebhookEndpointCreateRequest,
  WebhookEndpointId,
  WebhookEndpointList,
} from './types/webhooks.js';
import type { Transfer, TransferCreateRequest } from './types/transfers.js';
import type { Page } from './types/common.js';
import { LIMIT_MAX, LIMIT_MIN } from './types/common.js';
import {
  ApiError,
  NetworkError,
  TimeoutError,
  toSharkPayError,
} from './errors.js';
import { randomUuid } from './platform.js';

/** Version of this SDK (also sent as `user-agent: sharkpay-ts/<version>`). */
export const CLIENT_VERSION = '1.0.0';

/** A `fetch`-shaped function (the global `fetch` satisfies this). */
export type FetchLike = (input: string, init?: RequestInit) => Promise<Response>;

/** HTTP methods used by the v1 API surface. */
export type HttpMethod = 'GET' | 'POST' | 'DELETE';

/** Query parameter record (`undefined` values are dropped). */
export type QueryRecord = Record<string, string | number | boolean | undefined>;

/** Retry/backoff configuration. Pass `false` to opt out entirely. */
export interface RetryOptions {
  /** Retries *after* the first attempt (default 3). */
  maxRetries?: number | undefined;
  /** Backoff for the first retry (default 200ms; doubles per attempt). */
  baseDelayMs?: number | undefined;
  /** Backoff ceiling (default 5,000ms). */
  maxDelayMs?: number | undefined;
  /** Randomize delays within [delay/2, delay) (default true). */
  jitter?: boolean | undefined;
}

/** Options accepted by the `SharkPayClient` constructor. */
export interface SharkPayClientOptions {
  /**
   * Base URL of the API **including the version path**, e.g.
   * `https://api.sandbox.sharkpay.dev/v1` (the servers block of every v1
   * spec). A trailing slash is tolerated.
   */
  baseUrl: string;
  /** SharkPay API key — sent as `Authorization: Bearer <apiKey>`. */
  apiKey?: string | undefined;
  /** Alternative to `apiKey` (same `Bearer` header) for future OAuth-style tokens. */
  bearerToken?: string | undefined;
  /** Fetch implementation (default: the global `fetch`). Inject for tests/proxies. */
  fetchImpl?: FetchLike | undefined;
  /** Per-request timeout in ms (default 10,000). Aborts into `TimeoutError`. */
  timeoutMs?: number | undefined;
  /** Headers merged into every request (lower-cased; per-request headers win). */
  defaultHeaders?: Record<string, string> | undefined;
  /** Retry configuration; `false` disables retries (default: 3 retries with jitter). */
  retry?: RetryOptions | false | undefined;
  /** Override the idempotency key generator (default: `crypto.randomUUID`). */
  idempotencyKeyGenerator?: (() => string) | undefined;
  /** Sleep injection point for deterministic retry tests. */
  sleep?: ((ms: number) => Promise<void>) | undefined;
  /** Random injection point for deterministic jitter tests. */
  random?: (() => number) | undefined;
}

/** Successful response envelope returned by the low-level `request()`. */
export interface ApiResult<T> {
  /** Parsed JSON body (absent for 204 responses). */
  data: T;
  /** HTTP status (2xx). */
  status: number;
  /** `X-Request-Id` response header, when the server sent one. */
  requestId: string | null;
  /** `true` when the server flagged this response as an idempotent replay. */
  idempotentReplay: boolean;
}

/** Options for the low-level `request()` call. */
export interface RequestOptions {
  query?: QueryRecord | undefined;
  /** JSON request body (serialized with `JSON.stringify`). */
  body?: unknown;
  /** Explicit idempotency key; auto-generated for POSTs when omitted. */
  idempotencyKey?: string | undefined;
  headers?: Record<string, string> | undefined;
}

/** Mutation-specific options (all state-changing POSTs). */
export interface MutationRequestOptions {
  /** Explicit idempotency key; a fresh UUID is generated when omitted. */
  idempotencyKey?: string | undefined;
  headers?: Record<string, string> | undefined;
}

const DEFAULT_TIMEOUT_MS = 10_000;
const DEFAULT_MAX_RETRIES = 3;
const DEFAULT_BASE_DELAY_MS = 200;
const DEFAULT_MAX_DELAY_MS = 5_000;

interface ResolvedRetry {
  maxRetries: number;
  baseDelayMs: number;
  maxDelayMs: number;
  jitter: boolean;
}

/** Internal seam the per-resource clients call (implemented by {@link SharkPayClient}). */
interface Requester {
  request<T>(method: HttpMethod, path: string, options?: RequestOptions | undefined): Promise<ApiResult<T>>;
}

const defaultSleep = (ms: number): Promise<void> =>
  new Promise((resolve) => {
    setTimeout(resolve, ms);
  });

function normalizeBaseUrl(baseUrl: string): string {
  try {
    const parsed = new URL(baseUrl);
    if (parsed.protocol !== 'http:' && parsed.protocol !== 'https:') {
      throw new Error(`protocol must be http or https, got ${parsed.protocol}`);
    }
  } catch (cause) {
    throw new TypeError(
      `baseUrl must be an absolute http(s) URL including the version path, e.g. 'https://api.sharkpay.dev/v1' (got ${JSON.stringify(baseUrl)})`,
      { cause },
    );
  }
  return baseUrl.replace(/\/+$/, '');
}

function lowercaseHeaders(headers: Record<string, string>): Record<string, string> {
  const result: Record<string, string> = {};
  for (const [key, value] of Object.entries(headers)) {
    result[key.toLowerCase()] = value;
  }
  return result;
}

function assertLimit(limit: number | undefined): void {
  if (limit === undefined) {
    return;
  }
  if (!Number.isInteger(limit) || limit < LIMIT_MIN || limit > LIMIT_MAX) {
    throw new RangeError(`limit must be an integer between ${LIMIT_MIN} and ${LIMIT_MAX} (got ${String(limit)})`);
  }
}

/** Parse `Retry-After` (delay-seconds or HTTP-date) into milliseconds. */
function retryAfterMsFromHeaders(response: Response, nowMs: number): number | undefined {
  const header = response.headers.get('retry-after');
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

async function discardBody(response: Response): Promise<void> {
  try {
    await response.body?.cancel();
  } catch {
    // The body of a to-be-retried 5xx response is best-effort drained.
  }
}

// ---------------------------------------------------------------------------
// Per-resource clients
// ---------------------------------------------------------------------------

/** `client.payments` — POST /payments, GET /payments, GET /payments/{id}, POST /payments/{id}/cancel. */
export class PaymentsResource {
  private readonly api: Requester;

  constructor(api: Requester) {
    this.api = api;
  }

  /** Create a payment intent (operationId `createPayment`, scope `payments:write`). */
  async create(request: PaymentCreateRequest, options?: MutationRequestOptions): Promise<Payment> {
    const result = await this.api.request<Payment>('POST', '/payments', {
      body: request,
      ...options,
    });
    return result.data;
  }

  /** Retrieve a payment intent (`getPayment`, scope `payments:read`). */
  async get(id: PaymentId): Promise<Payment> {
    const result = await this.api.request<Payment>('GET', `/payments/${encodeURIComponent(id)}`);
    return result.data;
  }

  /** List payment intents, cursor-paginated (`listPayments`, scope `payments:read`). */
  async list(query: ListPaymentsQuery = {}): Promise<PaymentList> {
    assertLimit(query.limit);
    const result = await this.api.request<PaymentList>('GET', '/payments', { query });
    return result.data;
  }

  /**
   * Cancel an unconfirmed payment intent (`cancelPayment`, scope
   * `payments:write`). Only CREATED or PENDING_PROVIDER can be cancelled;
   * anything later is a 409 `state_conflict` (use the reversal flow
   * instead). Any active hold is released.
   */
  async cancel(id: PaymentId, options?: MutationRequestOptions): Promise<Payment> {
    const result = await this.api.request<Payment>(
      'POST',
      `/payments/${encodeURIComponent(id)}/cancel`,
      { ...options },
    );
    return result.data;
  }
}

/** `client.payouts` — POST /payouts, GET /payouts/{id}, POST /payouts/{id}/cancel. */
export class PayoutsResource {
  private readonly api: Requester;

  constructor(api: Requester) {
    this.api = api;
  }

  /** Create a payout (`createPayout`, scope `payouts:write`). */
  async create(request: PayoutCreateRequest, options?: MutationRequestOptions): Promise<Payout> {
    const result = await this.api.request<Payout>('POST', '/payouts', { body: request, ...options });
    return result.data;
  }

  /** Get payout status (`getPayout`, scope `payouts:read`). */
  async get(id: PayoutId): Promise<Payout> {
    const result = await this.api.request<Payout>('GET', `/payouts/${encodeURIComponent(id)}`);
    return result.data;
  }

  /**
   * Cancel a payout before the provider accepts it (`cancelPayout`, scope
   * `payouts:write`). Only CREATED or PENDING_RISK can be cancelled; later
   * states return 409 `state_conflict`. Any active hold is released.
   */
  async cancel(id: PayoutId, options?: MutationRequestOptions): Promise<Payout> {
    const result = await this.api.request<Payout>(
      'POST',
      `/payouts/${encodeURIComponent(id)}/cancel`,
      { ...options },
    );
    return result.data;
  }
}

/** `client.transfers` — POST /transfers (the only v1 transfer endpoint; execution is synchronous). */
export class TransfersResource {
  private readonly api: Requester;

  constructor(api: Requester) {
    this.api = api;
  }

  /**
   * Transfer between wallets (`createTransfer`, scope `transfers:write`).
   * V1 returns the terminal state (`SUCCEEDED`, or `FAILED` for pre-flight
   * rejection that never partially posted) synchronously.
   */
  async create(request: TransferCreateRequest, options?: MutationRequestOptions): Promise<Transfer> {
    const result = await this.api.request<Transfer>('POST', '/transfers', {
      body: request,
      ...options,
    });
    return result.data;
  }
}

/** `client.wallets` — GET /wallets, GET /wallets/{id}, GET /wallets/{id}/statement. */
export class WalletsResource {
  private readonly api: Requester;

  constructor(api: Requester) {
    this.api = api;
  }

  /** List wallets, cursor-paginated (`listWallets`, scope `wallets:read`). */
  async list(query: ListWalletsQuery = {}): Promise<WalletList> {
    assertLimit(query.limit);
    const result = await this.api.request<WalletList>('GET', '/wallets', { query });
    return result.data;
  }

  /** Read a wallet with its balance partitions (`getWallet`, scope `wallets:read`). */
  async get(id: WalletId): Promise<Wallet> {
    const result = await this.api.request<Wallet>('GET', `/wallets/${encodeURIComponent(id)}`);
    return result.data;
  }

  /**
   * Cursor-paginated ledger statement of the wallet (`getWalletStatement`,
   * scope `wallets:read`). Entries are immutable; corrections appear as
   * compensation (reversal/adjustment) lines.
   */
  async statement(id: WalletId, query: StatementQuery = {}): Promise<StatementList> {
    assertLimit(query.limit);
    const result = await this.api.request<StatementList>(
      'GET',
      `/wallets/${encodeURIComponent(id)}/statement`,
      { query },
    );
    return result.data;
  }
}

/** `client.fx` — POST /fx/quotes, POST /fx/convert. */
export class FxResource {
  private readonly api: Requester;

  constructor(api: Requester) {
    this.api = api;
  }

  /** Create a TTL'd FX quote (`createQuote`, scope `fx:read`). */
  async quote(request: QuoteCreateRequest, options?: MutationRequestOptions): Promise<Quote> {
    const result = await this.api.request<Quote>('POST', '/fx/quotes', { body: request, ...options });
    return result.data;
  }

  /**
   * Convert using a quote (`convert`, scope `fx:write`). V1 execution is
   * synchronous: the response carries `state: EXECUTED` and the ledger
   * entry id. Expired/locked/executed quotes return 409 `state_conflict`.
   */
  async convert(request: ConversionCreateRequest, options?: MutationRequestOptions): Promise<Conversion> {
    const result = await this.api.request<Conversion>('POST', '/fx/convert', {
      body: request,
      ...options,
    });
    return result.data;
  }
}

/** `client.webhooks` — POST/GET /webhook-endpoints, GET/DELETE /webhook-endpoints/{id}. */
export class WebhooksResource {
  private readonly api: Requester;

  constructor(api: Requester) {
    this.api = api;
  }

  /**
   * Register a webhook endpoint (`createWebhookEndpoint`, scope
   * `webhooks:manage`). The HMAC secret is returned in full **only** in
   * this response — store it securely.
   */
  async subscribe(
    request: WebhookEndpointCreateRequest,
    options?: MutationRequestOptions,
  ): Promise<WebhookEndpoint> {
    const result = await this.api.request<WebhookEndpoint>('POST', '/webhook-endpoints', {
      body: request,
      ...options,
    });
    return result.data;
  }

  /** List webhook endpoints, cursor-paginated (`listWebhookEndpoints`). */
  async list(query: ListWebhookEndpointsQuery = {}): Promise<WebhookEndpointList> {
    assertLimit(query.limit);
    const result = await this.api.request<WebhookEndpointList>('GET', '/webhook-endpoints', {
      query,
    });
    return result.data;
  }

  /** Retrieve a webhook endpoint — secret redacted (`getWebhookEndpoint`). */
  async get(id: WebhookEndpointId): Promise<WebhookEndpoint> {
    const result = await this.api.request<WebhookEndpoint>(
      'GET',
      `/webhook-endpoints/${encodeURIComponent(id)}`,
    );
    return result.data;
  }

  /**
   * Delete a webhook endpoint (`deleteWebhookEndpoint`). In-flight
   * deliveries complete; the endpoint is then removed. Returns 204 — no
   * body (and no idempotency key: the contract reserves those for POSTs).
   */
  async delete(id: WebhookEndpointId): Promise<void> {
    await this.api.request<void>('DELETE', `/webhook-endpoints/${encodeURIComponent(id)}`);
  }
}

// ---------------------------------------------------------------------------
// The client
// ---------------------------------------------------------------------------

/**
 * Typed client for the SharkPay v1 API.
 *
 * ```ts
 * const client = new SharkPayClient({
 *   baseUrl: 'https://api.sandbox.sharkpay.dev/v1',
 *   apiKey: process.env.SHARKPAY_API_KEY!,
 * });
 * const payment = await client.payments.create({
 *   amount_minor: 150_000,
 *   currency: 'KES',
 *   destination_wallet: 'wal_...',
 * });
 * ```
 */
export class SharkPayClient implements Requester {
  readonly payments: PaymentsResource;
  readonly payouts: PayoutsResource;
  readonly transfers: TransfersResource;
  readonly wallets: WalletsResource;
  readonly fx: FxResource;
  readonly webhooks: WebhooksResource;

  private readonly baseUrl: string;
  private readonly authHeader: string | undefined;
  private readonly fetchImpl: FetchLike;
  private readonly timeoutMs: number;
  private readonly defaultHeaders: Record<string, string>;
  private readonly retry: ResolvedRetry | null;
  private readonly generateIdempotencyKey: () => string;
  private readonly sleep: (ms: number) => Promise<void>;
  private readonly random: () => number;

  constructor(options: SharkPayClientOptions) {
    if (options.apiKey !== undefined && options.bearerToken !== undefined) {
      throw new TypeError('provide either apiKey or bearerToken, not both');
    }
    const token = options.apiKey ?? options.bearerToken;
    this.baseUrl = normalizeBaseUrl(options.baseUrl);
    this.authHeader = token !== undefined ? `Bearer ${token}` : undefined;
    this.fetchImpl = options.fetchImpl ?? globalThis.fetch;
    this.timeoutMs = options.timeoutMs ?? DEFAULT_TIMEOUT_MS;
    this.defaultHeaders = options.defaultHeaders === undefined
      ? {}
      : lowercaseHeaders(options.defaultHeaders);
    this.retry =
      options.retry === false
        ? null
        : {
            maxRetries: options.retry?.maxRetries ?? DEFAULT_MAX_RETRIES,
            baseDelayMs: options.retry?.baseDelayMs ?? DEFAULT_BASE_DELAY_MS,
            maxDelayMs: options.retry?.maxDelayMs ?? DEFAULT_MAX_DELAY_MS,
            jitter: options.retry?.jitter ?? true,
          };
    this.generateIdempotencyKey = options.idempotencyKeyGenerator ?? randomUuid;
    this.sleep = options.sleep ?? defaultSleep;
    this.random = options.random ?? Math.random;

    this.payments = new PaymentsResource(this);
    this.payouts = new PayoutsResource(this);
    this.transfers = new TransfersResource(this);
    this.wallets = new WalletsResource(this);
    this.fx = new FxResource(this);
    this.webhooks = new WebhooksResource(this);
  }

  /**
   * Low-level typed request. Resource methods are thin wrappers over this;
   * call it directly when you need `status` / `requestId` / the
   * `X-Idempotent-Replay` flag.
   *
   * POSTs automatically get an `Idempotency-Key` (yours, or a generated
   * UUID); retries of a request reuse the same key.
   */
  async request<T>(
    method: HttpMethod,
    path: string,
    options: RequestOptions = {},
  ): Promise<ApiResult<T>> {
    const url = this.buildUrl(path, options.query);
    const headers = this.buildHeaders(options);
    const bodyText = options.body === undefined ? undefined : JSON.stringify(options.body);
    if (bodyText !== undefined) {
      headers['content-type'] = 'application/json';
    }
    const idempotencyKey =
      method === 'POST' ? options.idempotencyKey ?? this.generateIdempotencyKey() : options.idempotencyKey;
    if (idempotencyKey !== undefined) {
      headers['idempotency-key'] = idempotencyKey;
    }
    const init: RequestInit = { method, headers, ...(bodyText !== undefined ? { body: bodyText } : {}) };

    for (let attempt = 0; ; attempt += 1) {
      let response: Response;
      try {
        response = await this.fetchWithTimeout(url, init);
      } catch (error) {
        // Network-level failures are retried like 5xx; timeouts are not
        // (the request may or may not have been processed — surface them).
        if (error instanceof NetworkError && this.canRetry(attempt)) {
          await this.sleep(this.backoffDelayMs(attempt));
          continue;
        }
        throw error;
      }
      if (response.status >= 500 && this.canRetry(attempt)) {
        await discardBody(response);
        await this.sleep(this.backoffDelayMs(attempt));
        continue;
      }
      return this.parseResponse<T>(response);
    }
  }

  private canRetry(attempt: number): boolean {
    return this.retry !== null && attempt < this.retry.maxRetries;
  }

  private backoffDelayMs(attempt: number): number {
    if (this.retry === null) {
      throw new Error('unreachable: backoffDelayMs called with retries disabled');
    }
    const exponential = Math.min(this.retry.baseDelayMs * 2 ** attempt, this.retry.maxDelayMs);
    if (!this.retry.jitter) {
      return exponential;
    }
    // Half-jitter: uniform in [delay/2, delay), keeping the ceiling intact.
    return Math.floor(exponential / 2 + this.random() * (exponential / 2));
  }

  private buildUrl(path: string, query: QueryRecord | undefined): string {
    let url = `${this.baseUrl}${path}`;
    if (query !== undefined) {
      const search = new URLSearchParams();
      for (const [key, value] of Object.entries(query)) {
        if (value === undefined || value === null) {
          continue;
        }
        search.append(key, String(value));
      }
      const queryString = search.toString();
      if (queryString.length > 0) {
        url = `${url}?${queryString}`;
      }
    }
    return url;
  }

  private buildHeaders(options: RequestOptions): Record<string, string> {
    const headers: Record<string, string> = {
      accept: 'application/json',
      'user-agent': `sharkpay-ts/${CLIENT_VERSION}`,
      ...this.defaultHeaders,
      ...(options.headers === undefined ? {} : lowercaseHeaders(options.headers)),
    };
    if (this.authHeader !== undefined && headers['authorization'] === undefined) {
      headers['authorization'] = this.authHeader;
    }
    return headers;
  }

  private async fetchWithTimeout(url: string, init: RequestInit): Promise<Response> {
    const controller = new AbortController();
    const timer = setTimeout(() => controller.abort(), this.timeoutMs);
    try {
      return await this.fetchImpl(url, { ...init, signal: controller.signal });
    } catch (error) {
      if (error instanceof Error && error.name === 'AbortError') {
        throw new TimeoutError({
          code: 'timeout',
          status: 0,
          message: `request to ${url} timed out after ${this.timeoutMs}ms`,
          cause: error,
        });
      }
      throw new NetworkError({
        code: 'network_error',
        status: 0,
        message: `request to ${url} failed: ${error instanceof Error ? error.message : String(error)}`,
        cause: error,
      });
    } finally {
      clearTimeout(timer);
    }
  }

  private async parseResponse<T>(response: Response): Promise<ApiResult<T>> {
    const text = await response.text();
    const requestId = response.headers.get('x-request-id');
    const idempotentReplay = response.headers.get('x-idempotent-replay') === 'true';

    if (response.ok) {
      if (text.length === 0) {
        return { data: undefined as unknown as T, status: response.status, requestId, idempotentReplay };
      }
      let parsed: unknown;
      try {
        parsed = JSON.parse(text);
      } catch (cause) {
        throw new ApiError({
          code: 'invalid_response',
          status: response.status,
          message: 'response body is not valid JSON',
          ...(requestId !== null ? { requestId } : {}),
          cause,
        });
      }
      return { data: parsed as T, status: response.status, requestId, idempotentReplay };
    }

    let body: unknown = null;
    if (text.length > 0) {
      try {
        body = JSON.parse(text);
      } catch {
        body = null;
      }
    }
    const retryAfterMs = retryAfterMsFromHeaders(response, Date.now());
    throw toSharkPayError({
      status: response.status,
      body,
      ...(retryAfterMs !== undefined ? { retryAfterMs } : {}),
      ...(requestId !== null ? { headerRequestId: requestId } : {}),
    });
  }
}

/**
 * Auto-paginate any cursor-based list endpoint into an async generator of
 * items. Stops when `next_cursor` is null/absent.
 *
 * ```ts
 * for await (const payment of paginate((cursor) => client.payments.list({ cursor }))) {
 *   ...
 * }
 * ```
 */
export async function* paginate<T>(
  fetchPage: (cursor: string | undefined) => Promise<Page<T>>,
): AsyncGenerator<T, void, undefined> {
  let cursor: string | undefined = undefined;
  do {
    const page = await fetchPage(cursor);
    yield* page.items;
    const next = page.next_cursor;
    cursor = typeof next === 'string' && next.length > 0 ? next : undefined;
  } while (cursor !== undefined);
}
