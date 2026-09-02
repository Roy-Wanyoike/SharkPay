/**
 * Typed resource functions over `SharkPayApiClient`, one file-level object per
 * contract: wallets.yaml, payments.yaml, payouts.yaml.
 *
 * Paths mirror the web console's SDK stubs exactly (apps/web/src/lib/api/sdk):
 *   wallets  → GET /wallets, GET /wallets/{id}/statement
 *   payments → GET /payments, GET /payments/{id},
 *              POST /payments, POST /payments/{id}/cancel
 *   payouts  → GET /payouts/{id}, POST /payouts, POST /payouts/{id}/cancel
 *
 * Money mutations REQUIRE the caller's idempotency key — the key must be
 * minted once per logical user intent (see src/screens/SendScreen.tsx) and
 * reused across manual retries, never regenerated per attempt.
 */

import type { SharkPayApiClient } from './client';
import type {
  ListPaymentsQuery,
  ListWalletsQuery,
  Page,
  Payment,
  PaymentCreateRequest,
  PaymentId,
  Payout,
  PayoutCreateRequest,
  PayoutId,
  StatementEntry,
  StatementQuery,
  Wallet,
} from './types';

export interface WalletsApi {
  listWallets(query?: ListWalletsQuery): Promise<Page<Wallet>>;
  getStatement(walletId: string, query?: StatementQuery): Promise<Page<StatementEntry>>;
}

export interface PaymentsApi {
  listPayments(query?: ListPaymentsQuery): Promise<Page<Payment>>;
  getPayment(id: PaymentId): Promise<Payment>;
  createPayment(request: PaymentCreateRequest, idempotencyKey: string): Promise<Payment>;
  cancelPayment(id: PaymentId, idempotencyKey: string): Promise<Payment>;
}

export interface PayoutsApi {
  getPayout(id: PayoutId): Promise<Payout>;
  createPayout(request: PayoutCreateRequest, idempotencyKey: string): Promise<Payout>;
  cancelPayout(id: PayoutId, idempotencyKey: string): Promise<Payout>;
}

export function createWalletsApi(client: SharkPayApiClient): WalletsApi {
  return {
    listWallets: (query) =>
      client.get<Page<Wallet>>('/wallets', {
        principal_id: query?.principal_id,
        currency: query?.currency,
        status: query?.status,
        limit: query?.limit,
        cursor: query?.cursor,
      }),
    getStatement: (walletId, query) =>
      client.get<Page<StatementEntry>>(
        `/wallets/${encodeURIComponent(walletId)}/statement`,
        { limit: query?.limit, cursor: query?.cursor },
      ),
  };
}

export function createPaymentsApi(client: SharkPayApiClient): PaymentsApi {
  return {
    listPayments: (query) =>
      client.get<Page<Payment>>('/payments', {
        state: query?.state,
        principal_id: query?.principal_id,
        created_from: query?.created_from,
        created_to: query?.created_to,
        limit: query?.limit,
        cursor: query?.cursor,
      }),
    getPayment: (id) => client.get<Payment>(`/payments/${encodeURIComponent(id)}`),
    createPayment: (request, idempotencyKey) =>
      client.post<Payment>('/payments', request, idempotencyKey),
    cancelPayment: (id, idempotencyKey) =>
      client.post<Payment>(`/payments/${encodeURIComponent(id)}/cancel`, undefined, idempotencyKey),
  };
}

export function createPayoutsApi(client: SharkPayApiClient): PayoutsApi {
  return {
    // NOTE: payouts.yaml has no GET /payouts list endpoint (contract gap
    // documented in src/api/types.ts) — the store tracks payouts created
    // on-device and refreshes them by id.
    getPayout: (id) => client.get<Payout>(`/payouts/${encodeURIComponent(id)}`),
    createPayout: (request, idempotencyKey) =>
      client.post<Payout>('/payouts', request, idempotencyKey),
    cancelPayout: (id, idempotencyKey) =>
      client.post<Payout>(`/payouts/${encodeURIComponent(id)}/cancel`, undefined, idempotencyKey),
  };
}

/** The full typed API surface over one client instance. */
export interface SharkPayApi {
  wallets: WalletsApi;
  payments: PaymentsApi;
  payouts: PayoutsApi;
}

export function createSharkPayApi(client: SharkPayApiClient): SharkPayApi {
  return {
    wallets: createWalletsApi(client),
    payments: createPaymentsApi(client),
    payouts: createPayoutsApi(client),
  };
}
