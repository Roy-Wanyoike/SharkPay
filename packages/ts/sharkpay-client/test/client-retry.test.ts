/**
 * Retry/backoff semantics with deterministic sleep + random injection:
 * 5xx and network failures retry with bounded exponential backoff + jitter;
 * 429 and timeouts do not; retries reuse the same Idempotency-Key.
 */

import { describe, expect, it } from 'vitest';
import { SharkPayClient } from '../src/client.js';
import type { FetchLike } from '../src/client.js';
import { ApiError, NetworkError, RateLimitError, TimeoutError } from '../src/errors.js';
import type { FetchResponder } from './helpers.js';
import { API_KEY, BASE_URL, PAYMENT_ID, captureError, createFetchStub, jsonResponse, paymentFixture } from './helpers.js';

function recordingSleep() {
  const delays: number[] = [];
  return { delays, sleep: (ms: number) => { delays.push(ms); return Promise.resolve(); } };
}

const HALF_RANDOM = (): number => 0.5;

const NO_JITTER_RANDOM = (): number => {
  throw new Error('jitter disabled: random must not be called');
};

interface MakeClientOptions {
  responders?: FetchResponder[];
  fetchImpl?: FetchLike;
  retry?: ConstructorParameters<typeof SharkPayClient>[0]['retry'];
  timeoutMs?: number;
}

function makeClient(options: MakeClientOptions) {
  const recording = recordingSleep();
  const stub = createFetchStub(...(options.responders ?? []));
  const client = new SharkPayClient({
    baseUrl: BASE_URL,
    apiKey: API_KEY,
    fetchImpl: options.fetchImpl ?? stub.fetch,
    retry: options.retry,
    timeoutMs: options.timeoutMs,
    sleep: recording.sleep,
    random: HALF_RANDOM,
  });
  return { client, calls: stub.calls, delays: recording.delays };
}

const fiveHundred = jsonResponse(
  { error: { code: 'internal_error', message: 'boom', request_id: 'req_x' } },
  { status: 500 },
);
const okPayment = jsonResponse(paymentFixture(), { status: 201 });

describe('5xx retry', () => {
  it('retries a 500 and succeeds on the second attempt, reusing the same idempotency key', async () => {
    const { client, calls, delays } = makeClient({ responders: [fiveHundred, okPayment] });
    const payment = await client.payments.create({
      amount_minor: 150000,
      currency: 'KES',
      destination_wallet: 'wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A',
    });
    expect(payment.id).toBe(PAYMENT_ID);
    expect(calls.length).toBe(2);
    const firstKey = calls[0]?.headers['idempotency-key'];
    const secondKey = calls[1]?.headers['idempotency-key'];
    expect(firstKey).toMatch(/^[0-9a-f-]{36}$/);
    expect(secondKey).toBe(firstKey);
    // base 200ms, attempt 0, half-jitter → 200/2 + 0.5 * 200/2 = 150
    expect(delays).toEqual([150]);
  });

  it('retries GETs as well', async () => {
    const { client, calls, delays } = makeClient({
      responders: [fiveHundred, jsonResponse(paymentFixture())],
    });
    const payment = await client.payments.get(PAYMENT_ID);
    expect(payment.state).toBe('PENDING_PROVIDER');
    expect(calls.length).toBe(2);
    expect(delays).toEqual([150]);
  });

  it('gives up after maxRetries (3) and throws the final ApiError', async () => {
    const { client, calls, delays } = makeClient({
      responders: [fiveHundred, fiveHundred, fiveHundred, fiveHundred],
    });
    const error = (await captureError(client.payments.get(PAYMENT_ID))) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('internal_error');
    expect(calls.length).toBe(4); // 1 attempt + 3 retries
    expect(delays).toEqual([150, 300, 600]); // 200/400/800 exponential, half-jittered
  });

  it('caps the exponential delay at maxDelayMs', async () => {
    const { client, delays } = makeClient({
      responders: [fiveHundred, fiveHundred, fiveHundred, fiveHundred],
      retry: { baseDelayMs: 100, maxDelayMs: 250 },
    });
    await expect(client.payments.get(PAYMENT_ID)).rejects.toBeInstanceOf(ApiError);
    // exponential 100, 200, 400 → capped 250; half-jittered: 75, 150, 187
    expect(delays).toEqual([75, 150, 187]);
  });

  it('jitter: false produces exact exponential delays and never calls random', async () => {
    const recording = recordingSleep();
    const stub = createFetchStub(fiveHundred, fiveHundred, fiveHundred, fiveHundred);
    const client = new SharkPayClient({
      baseUrl: BASE_URL,
      apiKey: API_KEY,
      fetchImpl: stub.fetch,
      retry: { baseDelayMs: 200, jitter: false },
      sleep: recording.sleep,
      random: NO_JITTER_RANDOM, // throws if invoked — proves jitter is off
    });
    await expect(client.payments.get(PAYMENT_ID)).rejects.toBeInstanceOf(ApiError);
    expect(recording.delays).toEqual([200, 400, 800]);
  });

  it('opts out entirely with retry: false', async () => {
    const { client, calls, delays } = makeClient({ responders: [fiveHundred], retry: false });
    const error = (await captureError(client.payments.get(PAYMENT_ID))) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(calls.length).toBe(1);
    expect(delays).toEqual([]);
  });

  it('maxRetries: 0 behaves like opting out', async () => {
    const { client, calls, delays } = makeClient({
      responders: [fiveHundred],
      retry: { maxRetries: 0 },
    });
    await expect(client.payments.get(PAYMENT_ID)).rejects.toBeInstanceOf(ApiError);
    expect(calls.length).toBe(1);
    expect(delays).toEqual([]);
  });

  it('does not retry 4xx — only 5xx', async () => {
    const forbidden = jsonResponse(
      { error: { code: 'forbidden', message: 'no scope', request_id: 'req_x' } },
      { status: 403 },
    );
    const { client, calls, delays } = makeClient({ responders: [forbidden] });
    await expect(client.payments.get(PAYMENT_ID)).rejects.toMatchObject({ status: 403 });
    expect(calls.length).toBe(1);
    expect(delays).toEqual([]);
  });

  it('does not retry 429 — surfaces RateLimitError immediately', async () => {
    const tooMany = jsonResponse(
      { error: { code: 'quota_exceeded', message: 'burst', request_id: 'req_x' } },
      { status: 429, headers: { 'Retry-After': '2' } },
    );
    const { client, calls, delays } = makeClient({ responders: [tooMany] });
    const error = (await captureError(client.payments.get(PAYMENT_ID))) as RateLimitError;
    expect(error).toBeInstanceOf(RateLimitError);
    expect(error.retryAfterMs).toBe(2000);
    expect(calls.length).toBe(1);
    expect(delays).toEqual([]);
  });
});

describe('network failures', () => {
  it('retries a fetch rejection and succeeds on the third attempt', async () => {
    let attempt = 0;
    const ok = createFetchStub(okPayment);
    const fetchImpl: FetchLike = (input, init) => {
      attempt += 1;
      if (attempt <= 2) {
        return Promise.reject(new TypeError('fetch failed'));
      }
      return ok.fetch(input, init);
    };
    const { client, delays } = makeClient({ fetchImpl });
    const payment = await client.payments.create({
      amount_minor: 1,
      currency: 'KES',
      destination_wallet: 'wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A',
    });
    expect(payment.id).toBe(PAYMENT_ID);
    expect(attempt).toBe(3);
    expect(delays).toEqual([150, 300]);
  });

  it('wraps persistent network failures in NetworkError with the cause attached', async () => {
    const fetchImpl: FetchLike = () => Promise.reject(new TypeError('fetch failed'));
    const { client, delays } = makeClient({ fetchImpl });
    const error = (await captureError(client.payments.get(PAYMENT_ID))) as NetworkError;
    expect(error).toBeInstanceOf(NetworkError);
    expect(error.code).toBe('network_error');
    expect(error.status).toBe(0);
    expect(error.cause).toBeInstanceOf(TypeError);
    expect(error.message).toContain(BASE_URL);
    expect(delays).toEqual([150, 300, 600]);
  });
});

describe('timeouts', () => {
  it('aborts after timeoutMs and throws TimeoutError without retrying', async () => {
    let fetchCalls = 0;
    const fetchImpl: FetchLike = (_input, init) => {
      fetchCalls += 1;
      return new Promise<Response>((_resolve, reject) => {
        const listener = () => reject(new DOMException('This operation was aborted', 'AbortError'));
        init?.signal?.addEventListener('abort', listener);
      });
    };
    const recording = recordingSleep();
    const client = new SharkPayClient({
      baseUrl: BASE_URL,
      apiKey: API_KEY,
      fetchImpl,
      timeoutMs: 20,
      sleep: recording.sleep,
      random: HALF_RANDOM,
    });
    const started = Date.now();
    const error = (await captureError(client.payments.get(PAYMENT_ID))) as TimeoutError;
    expect(error).toBeInstanceOf(TimeoutError);
    expect(error.code).toBe('timeout');
    expect(error.status).toBe(0);
    expect(error.cause).toBeInstanceOf(DOMException);
    expect(error.message).toContain('timed out after 20ms');
    expect(Date.now() - started).toBeGreaterThanOrEqual(15);
    expect(fetchCalls).toBe(1); // timeouts are not retried
    expect(recording.delays).toEqual([]);
  });
});
