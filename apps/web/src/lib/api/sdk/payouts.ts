import type { ApiClient } from "@/lib/api/client";
import type { Currency, Metadata, Money, Page, PageParams } from "@/lib/api/sdk/types";

/**
 * Payouts SDK — typed stubs over contracts/openapi/v1/payouts.yaml.
 * Paths: POST /payouts, GET /payouts, GET /payouts/{id}, POST /payouts/{id}/cancel.
 * NOTE: the merged payouts.yaml has no GET /payouts list path at v1.0.0 —
 * `listPayouts` is a provisional console-side read model until the contract
 * appends the list endpoint (contracts are append-only per ADR 003 §2).
 */

/** Payout rail (payouts.yaml PayoutRail — payouts go OUT, no honeycoin). */
export type PayoutRail = "mpesa" | "bank" | "on_chain";

/** Payout states (docs/STATE-MACHINES.md §2). */
export type PayoutState =
  | "CREATED"
  | "PENDING_RISK"
  | "PROCESSING"
  | "SENT"
  | "SUCCEEDED"
  | "FAILED"
  | "RETURNED"
  | "BLOCKED"
  | "CANCELLED";

export interface MpesaDestination {
  type: "mpesa";
  /** Subscriber number, E.164 (e.g. +254712345678). */
  msisdn: string;
}

export interface BankDestination {
  type: "bank";
  bank_code: string;
  account_number: string;
  account_name?: string;
  /** ISO 3166-1 alpha-2 country of the bank account. */
  country?: string;
}

export interface OnChainDestination {
  type: "on_chain";
  network: "base" | "ethereum" | "polygon";
  /** EVM address (hex, 20 bytes). */
  address: string;
}

export type PayoutDestination = MpesaDestination | BankDestination | OnChainDestination;

/** A payout (payouts.yaml Payout). */
export interface Payout {
  id: string;
  state: PayoutState;
  source_wallet: string;
  amount: Money;
  fee: Money;
  destination: PayoutDestination;
  rail: PayoutRail;
  metadata?: Metadata;
  failure_reason?: string;
  return_reason?: string;
  provider_ref?: string;
  expires_at?: string;
  created_at: string;
  updated_at?: string;
}

export interface PayoutCreateRequest {
  source_wallet: string;
  amount_minor: number;
  currency: Currency;
  destination: PayoutDestination;
  rail?: PayoutRail;
  metadata?: Metadata;
  expires_in_seconds?: number;
}

export interface PayoutListFilters extends PageParams {
  state?: PayoutState;
  rail?: PayoutRail;
}

export interface PayoutList extends Page<Payout> {}

export async function listPayouts(
  client: ApiClient,
  filters: PayoutListFilters = {},
): Promise<PayoutList> {
  return client.get<PayoutList>("/payouts", {
    state: filters.state,
    rail: filters.rail,
    limit: filters.limit,
    cursor: filters.cursor,
  });
}

export async function getPayout(client: ApiClient, id: string): Promise<Payout> {
  return client.get<Payout>(`/payouts/${encodeURIComponent(id)}`);
}

export async function createPayout(
  client: ApiClient,
  request: PayoutCreateRequest,
): Promise<Payout> {
  return client.post<Payout>("/payouts", request);
}

export async function cancelPayout(client: ApiClient, id: string): Promise<Payout> {
  return client.post<Payout>(`/payouts/${encodeURIComponent(id)}/cancel`);
}
