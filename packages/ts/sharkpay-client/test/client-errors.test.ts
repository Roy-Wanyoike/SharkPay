/**
 * Error mapping end-to-end through the client with a mocked fetch:
 * every documented v1 error status produces the right typed error class
 * with envelope fields attached.
 */

import { describe, expect, it } from 'vitest';
import { SharkPayClient } from '../src/client.js';
import {
  ApiError,
  AuthError,
  IdempotencyConflictError,
  RateLimitError,
  ValidationError,
} from '../src/errors.js';
import type { FetchResponder } from './helpers.js';
import {
  API_KEY,
  BASE_URL,
  PAYMENT_ID,
  REQUEST_ID,
  captureError,
  createFetchStub,
  jsonResponse,
} from './helpers.js';

function clientWith(responders: FetchResponder[]) {
  const stub = createFetchStub(...responders);
  const client = new SharkPayClient({
    baseUrl: BASE_URL,
    apiKey: API_KEY,
    fetchImpl: stub.fetch,
    retry: false,
  });
  return client;
}

const createRequest = {
  amount_minor: 150000,
  currency: 'KES' as const,
  destination_wallet: 'wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A',
};

function envelope(code: string, message: string, details?: Record<string, unknown>) {
  return { error: { code, message, request_id: REQUEST_ID, ...(details !== undefined ? { details } : {}) } };
}

describe('client error mapping', () => {
  it('400 validation_error → ValidationError with details', async () => {
    const client = clientWith([
      jsonResponse(envelope('validation_error', 'amount_minor: must be a positive integer.', { field: 'amount_minor' }), { status: 400 }),
    ]);
    const error = (await captureError(client.payments.create(createRequest))) as ValidationError;
    expect(error).toBeInstanceOf(ValidationError);
    expect(error.name).toBe('ValidationError');
    expect(error.code).toBe('validation_error');
    expect(error.status).toBe(400);
    expect(error.requestId).toBe(REQUEST_ID);
    expect(error.details).toEqual({ field: 'amount_minor' });
  });

  it('401 unauthorized → AuthError; 403 forbidden → AuthError', async () => {
    const unauthorized = clientWith([jsonResponse(envelope('unauthorized', 'Invalid API key.'), { status: 401 })]);
    const error401 = (await captureError(unauthorized.payments.get(PAYMENT_ID))) as AuthError;
    expect(error401).toBeInstanceOf(AuthError);
    expect(error401.code).toBe('unauthorized');

    const forbidden = clientWith([
      jsonResponse(envelope('forbidden', 'Key lacks required scope payments:write.', { required_scope: 'payments:write' }), { status: 403 }),
    ]);
    const error403 = (await captureError(forbidden.payments.create(createRequest))) as AuthError;
    expect(error403).toBeInstanceOf(AuthError);
    expect(error403.code).toBe('forbidden');
    expect(error403.details).toEqual({ required_scope: 'payments:write' });
  });

  it('404 not_found → ApiError with request_id from the envelope', async () => {
    const client = clientWith([
      jsonResponse(envelope('not_found', `Payment ${PAYMENT_ID} not found.`), { status: 404 }),
    ]);
    const error = (await captureError(client.payments.get(PAYMENT_ID))) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('not_found');
    expect(error.requestId).toBe(REQUEST_ID);
  });

  it('409 idempotency_conflict → IdempotencyConflictError', async () => {
    const client = clientWith([
      jsonResponse(
        envelope('idempotency_conflict', 'Idempotency-Key was already used with a different request payload.'),
        { status: 409 },
      ),
    ]);
    const error = (await captureError(
      client.payments.create(createRequest, { idempotencyKey: 'reused-key' }),
    )) as IdempotencyConflictError;
    expect(error).toBeInstanceOf(IdempotencyConflictError);
    expect(error.code).toBe('idempotency_conflict');
  });

  it('409 state_conflict → ApiError (cancel of a terminal payment)', async () => {
    const client = clientWith([
      jsonResponse(
        envelope('state_conflict', 'Payment is already in terminal state SUCCEEDED and cannot be cancelled.'),
        { status: 409 },
      ),
    ]);
    const error = (await captureError(client.payments.cancel(PAYMENT_ID))) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error).not.toBeInstanceOf(IdempotencyConflictError);
    expect(error.code).toBe('state_conflict');
  });

  it('422 insufficient_funds → ApiError with machine-readable details', async () => {
    const client = clientWith([
      jsonResponse(
        envelope('insufficient_funds', 'Wallet balance after holds is 1200, requested 5000.', {
          available_minor: 1200,
          requested_minor: 5000,
        }),
        { status: 422 },
      ),
    ]);
    const error = (await captureError(
      client.transfers.create({
        source_wallet: 'wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A',
        destination_wallet: 'wal_01H5G8K2M9Q7R4T3V2W6Y8B0C',
        amount_minor: 5000,
        currency: 'KES',
      }),
    )) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('insufficient_funds');
    expect(error.details).toEqual({ available_minor: 1200, requested_minor: 5000 });
  });

  it('429 quota_exceeded → RateLimitError with Retry-After seconds', async () => {
    const client = clientWith([
      jsonResponse(envelope('quota_exceeded', 'Payment creation burst limit of 50/min exceeded.'), {
        status: 429,
        headers: { 'Retry-After': '7' },
      }),
    ]);
    const error = (await captureError(client.payments.create(createRequest))) as RateLimitError;
    expect(error).toBeInstanceOf(RateLimitError);
    expect(error.code).toBe('quota_exceeded');
    expect(error.retryAfterMs).toBe(7000);
  });

  it('429 with an HTTP-date Retry-After converts to a relative delay', async () => {
    const retryAt = new Date(Date.now() + 30_000).toUTCString();
    const client = clientWith([
      jsonResponse(envelope('quota_exceeded', 'slow down'), { status: 429, headers: { 'Retry-After': retryAt } }),
    ]);
    const error = (await captureError(client.payments.create(createRequest))) as RateLimitError;
    expect(error.retryAfterMs).toBeGreaterThanOrEqual(29_000);
    expect(error.retryAfterMs).toBeLessThanOrEqual(31_000);
  });

  it('429 without Retry-After has retryAfterMs undefined', async () => {
    const client = clientWith([jsonResponse(envelope('quota_exceeded', 'slow down'), { status: 429 })]);
    const error = (await captureError(client.payments.create(createRequest))) as RateLimitError;
    expect(error.retryAfterMs).toBeUndefined();
  });

  it('500 → ApiError internal_error (retries disabled in this suite)', async () => {
    const client = clientWith([
      jsonResponse(envelope('internal_error', 'An unexpected error occurred.'), { status: 500 }),
    ]);
    const error = (await captureError(client.payments.get(PAYMENT_ID))) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('internal_error');
  });

  it('non-JSON error body falls back to a status-derived code and uses the header request id', async () => {
    const client = clientWith([
      new Response('gateway teapot', { status: 418, headers: { 'X-Request-Id': REQUEST_ID } }),
    ]);
    const error = (await captureError(client.payments.get(PAYMENT_ID))) as ApiError;
    expect(error).toBeInstanceOf(ApiError);
    expect(error.code).toBe('http_error');
    expect(error.message).toContain('418');
    expect(error.requestId).toBe(REQUEST_ID);
  });

  it('every mapped error carries a name and a stack', async () => {
    const client = clientWith([jsonResponse(envelope('not_found', 'nope'), { status: 404 })]);
    const error = (await captureError(client.payments.get(PAYMENT_ID))) as ApiError;
    expect(error.name).toBe('ApiError');
    expect(error.stack).toBeTypeOf('string');
  });
});
