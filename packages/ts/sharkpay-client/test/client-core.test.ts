/**
 * Client core: construction, auth, header discipline, idempotency keys,
 * the low-level request() surface (replay/request-id/204/invalid JSON) and
 * limit validation.
 */

import { describe, expect, it } from 'vitest';
import { SharkPayClient } from '../src/client.js';
import { ApiError } from '../src/errors.js';
import type { Payment } from '../src/index.js';
import type { FetchResponder } from './helpers.js';
import {
  API_KEY,
  BASE_URL,
  PAYMENT_ID,
  REQUEST_ID,
  createFetchStub,
  jsonResponse,
  paymentFixture,
  emptyResponse,
} from './helpers.js';

function makeClient(responders: FetchResponder[]) {
  const stub = createFetchStub(...responders);
  const client = new SharkPayClient({ baseUrl: BASE_URL, apiKey: API_KEY, fetchImpl: stub.fetch });
  return { client, calls: stub.calls };
}

describe('construction', () => {
  it('rejects an invalid baseUrl', () => {
    expect(() => new SharkPayClient({ baseUrl: 'not a url', apiKey: API_KEY })).toThrow(TypeError);
    expect(() => new SharkPayClient({ baseUrl: 'ftp://api.sharkpay.dev/v1', apiKey: API_KEY })).toThrow(
      TypeError,
    );
  });

  it('rejects apiKey and bearerToken together', () => {
    expect(
      () => new SharkPayClient({ baseUrl: BASE_URL, apiKey: API_KEY, bearerToken: 'tok' }),
    ).toThrow(/either apiKey or bearerToken/);
  });

  it('accepts a trailing slash on the baseUrl', async () => {
    const stub = createFetchStub(jsonResponse(paymentFixture()));
    const client = new SharkPayClient({ baseUrl: `${BASE_URL}/`, apiKey: API_KEY, fetchImpl: stub.fetch });
    await client.payments.get(PAYMENT_ID);
    expect(stub.calls[0]?.url).toBe(`${BASE_URL}/payments/${PAYMENT_ID}`);
  });

  it('bearerToken produces the same Bearer header as apiKey', async () => {
    const stub = createFetchStub(jsonResponse(paymentFixture()));
    const client = new SharkPayClient({ baseUrl: BASE_URL, bearerToken: 'tok123', fetchImpl: stub.fetch });
    await client.payments.get(PAYMENT_ID);
    expect(stub.calls[0]?.headers['authorization']).toBe('Bearer tok123');
  });

  it('sends no authorization header when unauthenticated', async () => {
    const stub = createFetchStub(jsonResponse(paymentFixture()));
    const client = new SharkPayClient({ baseUrl: BASE_URL, fetchImpl: stub.fetch });
    await client.payments.get(PAYMENT_ID);
    expect(stub.calls[0]?.headers['authorization']).toBeUndefined();
  });
});

describe('request discipline', () => {
  it('sends Bearer auth, accept, content-type and user-agent on a create', async () => {
    const { client, calls } = makeClient([jsonResponse(paymentFixture(), { status: 201 })]);
    await client.payments.create({
      amount_minor: 150000,
      currency: 'KES',
      destination_wallet: 'wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A',
    });
    const call = calls[0];
    expect(call?.method).toBe('POST');
    expect(call?.url).toBe(`${BASE_URL}/payments`);
    expect(call?.headers['authorization']).toBe(`Bearer ${API_KEY}`);
    expect(call?.headers['accept']).toBe('application/json');
    expect(call?.headers['content-type']).toBe('application/json');
    expect(call?.headers['user-agent']).toMatch(/^sharkpay-ts\/\d+\.\d+\.\d+$/);
    expect(call?.body).toBe(
      JSON.stringify({ amount_minor: 150000, currency: 'KES', destination_wallet: 'wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A' }),
    );
  });

  it('GETs carry no body and no idempotency key', async () => {
    const { client, calls } = makeClient([jsonResponse(paymentFixture())]);
    await client.payments.get(PAYMENT_ID);
    const call = calls[0];
    expect(call?.method).toBe('GET');
    expect(call?.body).toBeUndefined();
    expect(call?.headers['idempotency-key']).toBeUndefined();
  });

  it('defaultHeaders are merged (lower-cased) and per-request headers win', async () => {
    const stub = createFetchStub(jsonResponse(paymentFixture()));
    const client = new SharkPayClient({
      baseUrl: BASE_URL,
      apiKey: API_KEY,
      fetchImpl: stub.fetch,
      defaultHeaders: { 'X-Tenant': 'acme', 'x-scope': 'payments:read' },
    });
    await client.request('GET', '/payments', { headers: { 'x-scope': 'payments:write' } });
    const headers = stub.calls[0]?.headers;
    expect(headers?.['x-tenant']).toBe('acme');
    expect(headers?.['x-scope']).toBe('payments:write'); // per-request override
  });

  it('a per-request authorization header is respected over the API key', async () => {
    const stub = createFetchStub(jsonResponse(paymentFixture()));
    const client = new SharkPayClient({ baseUrl: BASE_URL, apiKey: API_KEY, fetchImpl: stub.fetch });
    await client.request('GET', '/payments', { headers: { Authorization: 'Bearer custom' } });
    expect(stub.calls[0]?.headers['authorization']).toBe('Bearer custom');
  });
});

describe('idempotency keys', () => {
  it('auto-generates a UUID idempotency key on mutations', async () => {
    const { client, calls } = makeClient([jsonResponse(paymentFixture(), { status: 201 })]);
    await client.payments.create({
      amount_minor: 150000,
      currency: 'KES',
      destination_wallet: 'wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A',
    });
    const key = calls[0]?.headers['idempotency-key'];
    expect(key).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$/);
  });

  it('uses a caller-supplied idempotency key verbatim', async () => {
    const { client, calls } = makeClient([jsonResponse(paymentFixture(), { status: 201 })]);
    await client.payments.create(
      {
        amount_minor: 150000,
        currency: 'KES',
        destination_wallet: 'wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A',
      },
      { idempotencyKey: '8f6c3b1e-9d2a-4f7b-8e5c-6a1b2c3d4e5f' },
    );
    expect(calls[0]?.headers['idempotency-key']).toBe('8f6c3b1e-9d2a-4f7b-8e5c-6a1b2c3d4e5f');
  });

  it('honours a custom idempotencyKeyGenerator (deterministic tests / merchant-side keys)', async () => {
    const stub = createFetchStub(jsonResponse(paymentFixture(), { status: 201 }));
    const client = new SharkPayClient({
      baseUrl: BASE_URL,
      apiKey: API_KEY,
      fetchImpl: stub.fetch,
      idempotencyKeyGenerator: () => 'merchant-key-1',
    });
    await client.payments.create({
      amount_minor: 1,
      currency: 'KES',
      destination_wallet: 'wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A',
    });
    expect(stub.calls[0]?.headers['idempotency-key']).toBe('merchant-key-1');
  });

  it('cancel also gets an idempotency key; webhook delete does not (contract reserves POSTs)', async () => {
    const stub = createFetchStub(
      jsonResponse(paymentFixture()),
      emptyResponse(204, { 'X-Request-Id': REQUEST_ID }),
    );
    const client = new SharkPayClient({ baseUrl: BASE_URL, apiKey: API_KEY, fetchImpl: stub.fetch });
    await client.payments.cancel(PAYMENT_ID);
    expect(stub.calls[0]?.headers['idempotency-key']).toMatch(/-/);
    await client.webhooks.delete('wh_01HZWR4Z7K8Q2N5M9X3V1B6Y0A');
    expect(stub.calls[1]?.method).toBe('DELETE');
    expect(stub.calls[1]?.headers['idempotency-key']).toBeUndefined();
  });
});

describe('low-level request()', () => {
  it('surfaces status, X-Request-Id and X-Idempotent-Replay', async () => {
    const stub = createFetchStub(
      jsonResponse(paymentFixture(), {
        status: 201,
        headers: { 'X-Request-Id': REQUEST_ID, 'X-Idempotent-Replay': 'true' },
      }),
    );
    const client = new SharkPayClient({ baseUrl: BASE_URL, apiKey: API_KEY, fetchImpl: stub.fetch });
    const result = await client.request<Payment>('POST', '/payments', {
      body: { amount_minor: 1, currency: 'KES', destination_wallet: 'wal_x' },
    });
    expect(result.status).toBe(201);
    expect(result.requestId).toBe(REQUEST_ID);
    expect(result.idempotentReplay).toBe(true);
    expect(result.data.id).toBe(PAYMENT_ID);
  });

  it('returns replay=false and null requestId when the server sends neither', async () => {
    const stub = createFetchStub(jsonResponse(paymentFixture()));
    const client = new SharkPayClient({ baseUrl: BASE_URL, apiKey: API_KEY, fetchImpl: stub.fetch });
    const result = await client.request('GET', `/payments/${PAYMENT_ID}`);
    expect(result.idempotentReplay).toBe(false);
    expect(result.requestId).toBeNull();
  });

  it('handles 204 No Content with void data (webhook delete)', async () => {
    const stub = createFetchStub(emptyResponse(204));
    const client = new SharkPayClient({ baseUrl: BASE_URL, apiKey: API_KEY, fetchImpl: stub.fetch });
    const result = await client.request<void>('DELETE', '/webhook-endpoints/wh_01HZWR4Z7K8Q2N5M9X3V1B6Y0A');
    expect(result.status).toBe(204);
    expect(result.data).toBeUndefined();
  });

  it('throws ApiError invalid_response on a 2xx body that is not JSON', async () => {
    const stub = createFetchStub(new Response('<html>oops</html>', { status: 200 }));
    const client = new SharkPayClient({ baseUrl: BASE_URL, apiKey: API_KEY, fetchImpl: stub.fetch });
    const error = await client.request('GET', '/payments').catch((e: unknown) => e);
    expect(error).toBeInstanceOf(ApiError);
    expect((error as ApiError).code).toBe('invalid_response');
    expect((error as ApiError).status).toBe(200);
    expect((error as ApiError).cause).toBeInstanceOf(Error);
  });
});

describe('limit validation', () => {
  it('rejects limit out of the 1..100 contract bounds client-side', async () => {
    const stub = createFetchStub();
    const client = new SharkPayClient({ baseUrl: BASE_URL, apiKey: API_KEY, fetchImpl: stub.fetch });
    await expect(client.payments.list({ limit: 0 })).rejects.toThrow(RangeError);
    await expect(client.payments.list({ limit: 101 })).rejects.toThrow(RangeError);
    await expect(client.payments.list({ limit: 50.5 })).rejects.toThrow(RangeError);
    await expect(client.wallets.list({ limit: 101 })).rejects.toThrow(/limit/);
    await expect(client.wallets.statement('wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A', { limit: 0 })).rejects.toThrow(
      RangeError,
    );
    await expect(client.webhooks.list({ limit: 101 })).rejects.toThrow(RangeError);
    expect(stub.calls.length).toBe(0); // never reached the wire
  });

  it('accepts limit within bounds', async () => {
    const stub = createFetchStub(jsonResponse({ items: [] }));
    const client = new SharkPayClient({ baseUrl: BASE_URL, apiKey: API_KEY, fetchImpl: stub.fetch });
    await client.payments.list({ limit: 100 });
    expect(stub.calls[0]?.url).toContain('limit=100');
  });
});
