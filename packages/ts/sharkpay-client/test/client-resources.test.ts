/**
 * One happy-path per resource family against a fetch mock returning
 * contract-shaped payloads (fixtures taken verbatim from the OpenAPI
 * examples), plus query serialization and pagination handling.
 */

import { describe, expect, it } from 'vitest';
import { paginate, SharkPayClient } from '../src/client.js';
import type { Payment } from '../src/index.js';
import {
  API_KEY,
  BASE_URL,
  CONVERSION_ID,
  ENDPOINT_ID,
  ENTRY_ID,
  PAYMENT_ID,
  PAYOUT_ID,
  QUOTE_ID,
  REQUEST_ID,
  TRANSFER_ID,
  WALLET_A,
  WALLET_B,
  conversionFixture,
  createFetchStub,
  emptyResponse,
  jsonResponse,
  paymentFixture,
  payoutFixture,
  quoteFixture,
  statementPageFixture,
  transferFixture,
  walletFixture,
  webhookEndpointFixture,
} from './helpers.js';

function makeClient(responders: Parameters<typeof createFetchStub>) {
  const stub = createFetchStub(...responders);
  const client = new SharkPayClient({ baseUrl: BASE_URL, apiKey: API_KEY, fetchImpl: stub.fetch });
  return { client, calls: stub.calls };
}

describe('payments resource', () => {
  it('create posts the contract example body and returns the intent', async () => {
    const { client, calls } = makeClient([jsonResponse(paymentFixture(), { status: 201 })]);
    const payment = await client.payments.create({
      amount_minor: 150000,
      currency: 'KES',
      destination_wallet: WALLET_A,
      rail: 'honeycoin',
      metadata: { order_id: 'A-7731' },
      expires_in_seconds: 900,
    });
    expect(payment).toEqual(paymentFixture());
    expect(calls[0]?.url).toBe(`${BASE_URL}/payments`);
    expect(calls[0]?.method).toBe('POST');
    expect(JSON.parse(calls[0]?.body ?? '{}')).toEqual({
      amount_minor: 150000,
      currency: 'KES',
      destination_wallet: WALLET_A,
      rail: 'honeycoin',
      metadata: { order_id: 'A-7731' },
      expires_in_seconds: 900,
    });
  });

  it('get retrieves the intent by id', async () => {
    const { client, calls } = makeClient([jsonResponse(paymentFixture())]);
    const payment = await client.payments.get(PAYMENT_ID);
    expect(payment.id).toBe(PAYMENT_ID);
    expect(payment.amount).toEqual({ amount_minor: 150000, currency: 'KES', exponent: 2 });
    expect(calls[0]?.url).toBe(`${BASE_URL}/payments/${PAYMENT_ID}`);
  });

  it('list serializes every documented filter', async () => {
    const { client, calls } = makeClient([jsonResponse({ items: [paymentFixture()], next_cursor: null })]);
    const page = await client.payments.list({
      state: 'PENDING_PROVIDER',
      principal_id: '0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d',
      created_from: '2026-09-01T00:00:00Z',
      created_to: '2026-09-02T00:00:00Z',
      limit: 25,
      cursor: 'CURSOR_1',
    });
    expect(page.items.length).toBe(1);
    expect(page.next_cursor).toBeNull();
    const url = new URL(calls[0]?.url ?? 'http://x');
    expect(url.pathname).toBe('/v1/payments');
    expect(url.searchParams.get('state')).toBe('PENDING_PROVIDER');
    expect(url.searchParams.get('principal_id')).toBe('0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d');
    expect(url.searchParams.get('created_from')).toBe('2026-09-01T00:00:00Z');
    expect(url.searchParams.get('created_to')).toBe('2026-09-02T00:00:00Z');
    expect(url.searchParams.get('limit')).toBe('25');
    expect(url.searchParams.get('cursor')).toBe('CURSOR_1');
    expect([...url.searchParams.keys()].sort()).toEqual(
      ['created_from', 'created_to', 'cursor', 'limit', 'principal_id', 'state'].sort(),
    );
  });

  it('list with no query sends a bare URL', async () => {
    const { client, calls } = makeClient([jsonResponse({ items: [] })]);
    await client.payments.list();
    expect(calls[0]?.url).toBe(`${BASE_URL}/payments`);
  });

  it('cancel posts to the cancel path and returns the cancelled intent', async () => {
    const cancelled = { ...paymentFixture(), state: 'CANCELLED' as const };
    const { client, calls } = makeClient([jsonResponse(cancelled)]);
    const payment = await client.payments.cancel(PAYMENT_ID);
    expect(payment.state).toBe('CANCELLED');
    expect(calls[0]?.method).toBe('POST');
    expect(calls[0]?.url).toBe(`${BASE_URL}/payments/${PAYMENT_ID}/cancel`);
    expect(calls[0]?.headers['idempotency-key']).toBeDefined();
  });
});

describe('payouts resource', () => {
  it('create posts an mpesa destination verbatim', async () => {
    const { client, calls } = makeClient([jsonResponse(payoutFixture(), { status: 201 })]);
    const payout = await client.payouts.create({
      source_wallet: WALLET_A,
      amount_minor: 500000,
      currency: 'KES',
      destination: { type: 'mpesa', msisdn: '+254712345678' },
      metadata: { invoice: 'INV-991' },
    });
    expect(payout).toEqual(payoutFixture());
    expect(JSON.parse(calls[0]?.body ?? '{}')).toEqual({
      source_wallet: WALLET_A,
      amount_minor: 500000,
      currency: 'KES',
      destination: { type: 'mpesa', msisdn: '+254712345678' },
      metadata: { invoice: 'INV-991' },
    });
  });

  it('create posts an on_chain destination (network + address discriminated)', async () => {
    const onChain = {
      ...payoutFixture(),
      amount: { amount_minor: 25000000, currency: 'USDC', exponent: 6 },
      fee: { amount_minor: 275000, currency: 'USDC', exponent: 6 },
      destination: { type: 'on_chain', network: 'base', address: '0x8f6c3b1e9d2a4f7b8e5c6a1b2c3d4e5f6a7b8c9d' },
      rail: 'on_chain' as const,
    };
    const { client, calls } = makeClient([jsonResponse(onChain, { status: 201 })]);
    const payout = await client.payouts.create({
      source_wallet: WALLET_A,
      amount_minor: 25000000,
      currency: 'USDC',
      destination: {
        type: 'on_chain',
        network: 'base',
        address: '0x8f6c3b1e9d2a4f7b8e5c6a1b2c3d4e5f6a7b8c9d',
      },
    });
    expect(payout.destination).toEqual(onChain.destination);
    if (payout.destination.type === 'on_chain') {
      expect(payout.destination.network).toBe('base');
    }
    expect(JSON.parse(calls[0]?.body ?? '{}').destination).toEqual(onChain.destination);
  });

  it('get retrieves the payout by id', async () => {
    const { client, calls } = makeClient([jsonResponse(payoutFixture())]);
    const payout = await client.payouts.get(PAYOUT_ID);
    expect(payout.id).toBe(PAYOUT_ID);
    expect(calls[0]?.url).toBe(`${BASE_URL}/payouts/${PAYOUT_ID}`);
  });

  it('cancel posts to the cancel path', async () => {
    const cancelled = { ...payoutFixture(), state: 'CANCELLED' as const };
    const { client, calls } = makeClient([jsonResponse(cancelled)]);
    const payout = await client.payouts.cancel(PAYOUT_ID);
    expect(payout.state).toBe('CANCELLED');
    expect(calls[0]?.url).toBe(`${BASE_URL}/payouts/${PAYOUT_ID}/cancel`);
  });
});

describe('transfers resource', () => {
  it('create returns the synchronous terminal state with entry_id', async () => {
    const { client, calls } = makeClient([jsonResponse(transferFixture(), { status: 201 })]);
    const transfer = await client.transfers.create({
      source_wallet: WALLET_A,
      destination_wallet: WALLET_B,
      amount_minor: 250000,
      currency: 'KES',
      metadata: { reason: 'invoice-settlement' },
    });
    expect(transfer.id).toBe(TRANSFER_ID);
    expect(transfer.state).toBe('SUCCEEDED');
    if (transfer.state === 'SUCCEEDED') {
      expect(transfer.entry_id).toBe(ENTRY_ID);
    } else {
      throw new Error('expected the SUCCEEDED arm of the Transfer union');
    }
    expect(calls[0]?.url).toBe(`${BASE_URL}/transfers`);
  });
});

describe('wallets resource', () => {
  it('list serializes the wallet filters', async () => {
    const { client, calls } = makeClient([jsonResponse({ items: [walletFixture()] })]);
    const page = await client.wallets.list({ currency: 'KES', status: 'active', limit: 10 });
    expect(page.items[0]?.id).toBe(WALLET_A);
    const url = new URL(calls[0]?.url ?? 'http://x');
    expect(url.searchParams.get('currency')).toBe('KES');
    expect(url.searchParams.get('status')).toBe('active');
    expect(url.searchParams.get('limit')).toBe('10');
  });

  it('get returns the wallet with its balance partitions', async () => {
    const { client } = makeClient([jsonResponse(walletFixture())]);
    const wallet = await client.wallets.get(WALLET_A);
    expect(wallet.balances).toEqual({
      available: { amount_minor: 1250000, currency: 'KES', exponent: 2 },
      pending: { amount_minor: 0, currency: 'KES', exponent: 2 },
      held: { amount_minor: 50000, currency: 'KES', exponent: 2 },
    });
  });

  it('statement returns the paginated ledger page', async () => {
    const { client, calls } = makeClient([
      jsonResponse(statementPageFixture('CURSOR_2'), { headers: { 'X-Request-Id': REQUEST_ID } }),
    ]);
    const page = await client.wallets.statement(WALLET_A, { limit: 2, cursor: 'CURSOR_1' });
    expect(page.items.length).toBe(2);
    expect(page.items[0]?.entry_type).toBe('capture');
    expect(page.items[0]?.direction).toBe('credit');
    expect(page.items[1]?.entry_type).toBe('fee');
    expect(page.next_cursor).toBe('CURSOR_2');
    const url = new URL(calls[0]?.url ?? 'http://x');
    expect(url.pathname).toBe(`/v1/wallets/${WALLET_A}/statement`);
    expect(url.searchParams.get('limit')).toBe('2');
    expect(url.searchParams.get('cursor')).toBe('CURSOR_1');
  });
});

describe('fx resource', () => {
  it('quote posts the quote request and returns the TTLd quote', async () => {
    const { client, calls } = makeClient([jsonResponse(quoteFixture(), { status: 201 })]);
    const quote = await client.fx.quote({
      amount_minor: 15000000,
      base_currency: 'KES',
      quote_currency: 'USD',
    });
    expect(quote.id).toBe(QUOTE_ID);
    expect(quote.rate).toEqual({ value_minor: 7719, exponent: 4, base_currency: 'KES', quote_currency: 'USD' });
    expect(JSON.parse(calls[0]?.body ?? '{}')).toEqual({
      amount_minor: 15000000,
      base_currency: 'KES',
      quote_currency: 'USD',
    });
  });

  it('convert posts the conversion request and returns the executed conversion', async () => {
    const { client, calls } = makeClient([jsonResponse(conversionFixture(), { status: 201 })]);
    const conversion = await client.fx.convert({
      quote_id: QUOTE_ID,
      source_wallet: WALLET_A,
      destination_wallet: WALLET_B,
    });
    expect(conversion.id).toBe(CONVERSION_ID);
    expect(conversion.state).toBe('EXECUTED');
    expect(conversion.entry_id).toBe(conversionFixture().entry_id);
    expect(calls[0]?.url).toBe(`${BASE_URL}/fx/convert`);
  });
});

describe('webhooks resource', () => {
  it('subscribe registers the endpoint and returns the full secret', async () => {
    const { client, calls } = makeClient([jsonResponse(webhookEndpointFixture(), { status: 201 })]);
    const endpoint = await client.webhooks.subscribe({
      url: 'https://merchant.example.com/sharkpay/webhooks',
      events: ['payment.created', 'payment.succeeded', 'payment.failed'],
      secret: 'whsec_5f8a2b9c1d4e6f7a8b9c0d1e2f3a4b5c',
    });
    expect(endpoint.id).toBe(ENDPOINT_ID);
    expect(endpoint.secret).toBe('whsec_5f8a2b9c1d4e6f7a8b9c0d1e2f3a4b5c');
    expect(calls[0]?.url).toBe(`${BASE_URL}/webhook-endpoints`);
  });

  it('list and get return endpoints (secret redacted server-side)', async () => {
    const { client, calls } = makeClient([
      jsonResponse({ items: [{ ...webhookEndpointFixture(), secret: undefined }], next_cursor: null }),
      jsonResponse({ ...webhookEndpointFixture(), secret: undefined }),
    ]);
    const page = await client.webhooks.list({ limit: 50 });
    expect(page.items[0]?.secret).toBeUndefined();
    const endpoint = await client.webhooks.get(ENDPOINT_ID);
    expect(endpoint.state).toBe('active');
    expect(calls[1]?.url).toBe(`${BASE_URL}/webhook-endpoints/${ENDPOINT_ID}`);
  });

  it('delete resolves to void on 204', async () => {
    const { client, calls } = makeClient([
      emptyResponse(204, { 'X-Request-Id': REQUEST_ID }),
    ]);
    await expect(client.webhooks.delete(ENDPOINT_ID)).resolves.toBeUndefined();
    expect(calls[0]?.method).toBe('DELETE');
    expect(calls[0]?.url).toBe(`${BASE_URL}/webhook-endpoints/${ENDPOINT_ID}`);
  });
});

describe('paginate helper', () => {
  it('walks every page until next_cursor is null', async () => {
    const pages = [
      { items: [paymentFixture()], next_cursor: 'C2' },
      { items: [{ ...paymentFixture(), id: 'pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0B' }], next_cursor: 'C3' },
      { items: [], next_cursor: null },
    ];
    let pageCalls = 0;
    const client = new SharkPayClient({
      baseUrl: BASE_URL,
      apiKey: API_KEY,
      fetchImpl: () => {
        const page = pages[pageCalls];
        pageCalls += 1;
        return Promise.resolve(jsonResponse(page));
      },
    });
    const collected: Payment[] = [];
    for await (const payment of paginate((cursor) => client.payments.list({ cursor }))) {
      collected.push(payment);
    }
    expect(pageCalls).toBe(3);
    expect(collected.map((p) => p.id)).toEqual([PAYMENT_ID, 'pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0B']);
  });

  it('stops when next_cursor is absent (single final page)', async () => {
    let pageCalls = 0;
    const client = new SharkPayClient({
      baseUrl: BASE_URL,
      apiKey: API_KEY,
      fetchImpl: () => {
        pageCalls += 1;
        return Promise.resolve(jsonResponse(statementPageFixture()));
      },
    });
    const entries = [];
    for await (const entry of paginate((cursor) => client.wallets.statement(WALLET_A, { cursor }))) {
      entries.push(entry);
    }
    expect(pageCalls).toBe(1);
    expect(entries.length).toBe(2);
  });
});
