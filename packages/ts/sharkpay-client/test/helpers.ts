/**
 * Test helpers: a recording fetch stub plus contract-shaped fixtures taken
 * verbatim from the OpenAPI examples in contracts/openapi/v1/*.yaml.
 */

import type { FetchLike } from '../src/client.js';
import type { Payment } from '../src/index.js';
import type { Payout } from '../src/index.js';
import type { Transfer } from '../src/index.js';
import type { Wallet } from '../src/index.js';
import type { Quote, Conversion } from '../src/index.js';
import type { WebhookEndpoint } from '../src/index.js';
import type { StatementList } from '../src/index.js';

export interface RecordedCall {
  url: string;
  method: string;
  headers: Record<string, string>;
  body: string | undefined;
}

export type FetchResponder = Response | Error | ((call: RecordedCall) => Response);

export interface FetchStub {
  fetch: FetchLike;
  calls: RecordedCall[];
}

/**
 * Sequential fetch stub: each call records url/method/headers/body and
 * consumes the next responder. A `Response` is resolved, an `Error` is
 * rejected (network failure), a function is invoked with the call.
 */
/**
 * Re-materialises a stub Response: reads its body once (cached), then hands
 * every fetch call a brand-new Response with the same status/headers/body —
 * single-use bodies never block retry tests.
 */
const responseBodyCache = new WeakMap<Response, string>();

async function materializeResponse(responder: Response): Promise<Response> {
  let body = responseBodyCache.get(responder);
  if (body === undefined) {
    body = await responder.text();
    responseBodyCache.set(responder, body);
  }
  // 204/205/304 are null-body statuses: a string body (even "") makes the
  // Response constructor throw, which the stub would surface as a phantom
  // NetworkError that the client dutifully retries.
  const nullBody = responder.status === 204 || responder.status === 205 || responder.status === 304;
  return new Response(nullBody ? null : body, {
    status: responder.status,
    statusText: responder.statusText,
    headers: responder.headers,
  });
}

export function createFetchStub(...responders: FetchResponder[]): FetchStub {
  const calls: RecordedCall[] = [];
  let index = 0;
  const fetch = (input: string, init?: RequestInit): Promise<Response> => {
    const headers = (init?.headers ?? {}) as Record<string, string>;
    const call: RecordedCall = {
      url: input,
      method: init?.method ?? 'GET',
      headers,
      body: typeof init?.body === 'string' ? init.body : undefined,
    };
    calls.push(call);
    const responder = responders[index];
    index += 1;
    if (responder === undefined) {
      return Promise.reject(new Error(`unexpected extra fetch #${index}: ${input}`));
    }
    if (responder instanceof Error) {
      return Promise.reject(responder);
    }
    if (typeof responder === 'function') {
      return Promise.resolve(responder(call));
    }
    if (responder instanceof Response) {
      // Bodies are single-use and stubs may serve the same Response to
      // several attempts (retry tests). clone() is NOT safe here: the
      // client drains retried 5xx bodies with body.cancel(), and a later
      // clone().text() deadlocks (undici tee semantics — verified). So each
      // call re-materialises a fresh Response from the cached body text.
      return materializeResponse(responder);
    }
    return Promise.resolve(responder);
  };
  return { fetch, calls };
}

export function jsonResponse(
  body: unknown,
  init?: { status?: number; headers?: Record<string, string> },
): Response {
  return new Response(JSON.stringify(body), {
    status: init?.status ?? 200,
    ...(init?.headers !== undefined ? { headers: init.headers } : {}),
  });
}

export function emptyResponse(status: number, headers?: Record<string, string>): Response {
  return new Response(null, {
    status,
    ...(headers !== undefined ? { headers } : {}),
  });
}

/** Capture the rejection of a promise that is expected to fail (fails the test if it resolves). */
export async function captureError(promise: Promise<unknown>): Promise<unknown> {
  try {
    await promise;
  } catch (error) {
    return error;
  }
  return new Error('expected the request to fail, but it resolved');
}

export const BASE_URL = 'https://api.sandbox.sharkpay.dev/v1';
export const API_KEY = 'sp_test_0123456789abcdef';
export const PAYMENT_ID = 'pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A';
export const PAYOUT_ID = 'pot_01HZWR4Z7K8Q2N5M9X3V1B6Y0A';
export const TRANSFER_ID = 'trf_01HZWR4Z7K8Q2N5M9X3V1B6Y0A';
export const WALLET_A = 'wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A';
export const WALLET_B = 'wal_01H5G8K2M9Q7R4T3V2W6Y8B0C';
export const QUOTE_ID = 'fxq_01HZWR4Z7K8Q2N5M9X3V1B6Y0A';
export const CONVERSION_ID = 'cnv_01HZWR4Z7K8Q2N5M9X3V1B6Y0A';
export const ENDPOINT_ID = 'wh_01HZWR4Z7K8Q2N5M9X3V1B6Y0A';
export const REQUEST_ID = 'req_01HZXK2P9Q5M8R4T3V2W6Y8B0C';
export const ENTRY_ID = '0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d';

/** payments.yaml POST /payments 201 example (pendingProvider). */
export function paymentFixture(): Payment {
  return {
    id: PAYMENT_ID,
    state: 'PENDING_PROVIDER',
    amount: { amount_minor: 150000, currency: 'KES', exponent: 2 },
    fee: { amount_minor: 750, currency: 'KES', exponent: 2 },
    destination_wallet: WALLET_A,
    rail: 'honeycoin',
    metadata: { order_id: 'A-7731' },
    next_action: { type: 'none' },
    expires_at: '2026-09-01T10:15:00Z',
    created_at: '2026-09-01T10:00:00Z',
  };
}

/** payments.yaml GET /payments/{id} terminal FAILED shape (PaymentFailed arm). */
export function failedPaymentFixture(): Payment {
  return {
    ...paymentFixture(),
    state: 'FAILED',
    failure_reason: 'provider_rejected: insufficient liquidity',
  };
}

/** payouts.yaml POST /payouts 201 example (processing / mpesa). */
export function payoutFixture(): Payout {
  return {
    id: PAYOUT_ID,
    state: 'PENDING_RISK',
    source_wallet: WALLET_A,
    amount: { amount_minor: 500000, currency: 'KES', exponent: 2 },
    fee: { amount_minor: 5500, currency: 'KES', exponent: 2 },
    destination: { type: 'mpesa', msisdn: '+254712345678' },
    rail: 'mpesa',
    metadata: { invoice: 'INV-991' },
    expires_at: '2026-09-01T10:15:00Z',
    created_at: '2026-09-01T10:00:00Z',
  };
}

/** transfers.yaml POST /transfers 201 example (succeeded). */
export function transferFixture(): Transfer {
  return {
    id: TRANSFER_ID,
    state: 'SUCCEEDED',
    source_wallet: WALLET_A,
    destination_wallet: WALLET_B,
    amount: { amount_minor: 250000, currency: 'KES', exponent: 2 },
    fee: { amount_minor: 0, currency: 'KES', exponent: 2 },
    entry_id: ENTRY_ID,
    metadata: { reason: 'invoice-settlement' },
    created_at: '2026-09-01T10:00:00Z',
  };
}

/** wallets.yaml GET /wallets/{id} 200 example (kes). */
export function walletFixture(): Wallet {
  return {
    id: WALLET_A,
    principal_id: '0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d',
    currency: 'KES',
    status: 'active',
    balances: {
      available: { amount_minor: 1250000, currency: 'KES', exponent: 2 },
      pending: { amount_minor: 0, currency: 'KES', exponent: 2 },
      held: { amount_minor: 50000, currency: 'KES', exponent: 2 },
    },
    created_at: '2026-08-30T09:00:00Z',
  };
}

/** fx.yaml POST /fx/quotes 201 example (quoted). */
export function quoteFixture(): Quote {
  return {
    id: QUOTE_ID,
    state: 'QUOTED',
    base_currency: 'KES',
    quote_currency: 'USD',
    source_amount: { amount_minor: 15000000, currency: 'KES', exponent: 2 },
    target_amount: { amount_minor: 1157900, currency: 'USD', exponent: 2 },
    rate: { value_minor: 7719, exponent: 4, base_currency: 'KES', quote_currency: 'USD' },
    expires_at: '2026-09-01T10:01:00Z',
    created_at: '2026-09-01T10:00:00Z',
  };
}

/** fx.yaml POST /fx/convert 201 example (executed). */
export function conversionFixture(): Conversion {
  return {
    id: CONVERSION_ID,
    state: 'EXECUTED',
    quote_id: QUOTE_ID,
    source_wallet: WALLET_A,
    destination_wallet: WALLET_B,
    source_amount: { amount_minor: 15000000, currency: 'KES', exponent: 2 },
    target_amount: { amount_minor: 1157900, currency: 'USD', exponent: 2 },
    rate: { value_minor: 7719, exponent: 4, base_currency: 'KES', quote_currency: 'USD' },
    entry_id: ENTRY_ID,
    created_at: '2026-09-01T10:00:30Z',
  };
}

/** webhooks.yaml POST /webhook-endpoints shape (secret only in creation response). */
export function webhookEndpointFixture(): WebhookEndpoint {
  return {
    id: ENDPOINT_ID,
    url: 'https://merchant.example.com/sharkpay/webhooks',
    events: ['payment.created', 'payment.succeeded', 'payment.failed'],
    state: 'active',
    secret: 'whsec_5f8a2b9c1d4e6f7a8b9c0d1e2f3a4b5c',
    created_at: '2026-09-01T10:00:00Z',
  };
}

/** Statement page for GET /wallets/{id}/statement. */
export function statementPageFixture(nextCursor?: string): StatementList {
  return {
    items: [
      {
        id: 'pst_01HZWR4Z7K8Q2N5M9X3V1B6Y0A',
        entry_id: ENTRY_ID,
        entry_type: 'capture',
        direction: 'credit',
        amount: { amount_minor: 150000, currency: 'KES', exponent: 2 },
        balance_after: { amount_minor: 1250000, currency: 'KES', exponent: 2 },
        source: 'payments',
        source_ref: '0192a7c5-1a2b-7c3d-9e4f-8a5b6c7d8e9f',
        reason: 'payment captured',
        created_at: '2026-09-01T10:00:05Z',
      },
      {
        id: 'pst_01HZWR4Z7K8Q2N5M9X3V1B6Y0B',
        entry_id: ENTRY_ID,
        entry_type: 'fee',
        direction: 'debit',
        amount: { amount_minor: 750, currency: 'KES', exponent: 2 },
        balance_after: { amount_minor: 1249250, currency: 'KES', exponent: 2 },
        source: 'fees',
        source_ref: '0192a7c5-1a2b-7c3d-9e4f-8a5b6c7d8e9f',
        created_at: '2026-09-01T10:00:05Z',
      },
    ],
    ...(nextCursor !== undefined ? { next_cursor: nextCursor } : {}),
  };
}
