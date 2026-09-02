import type { ApiClient } from "@/lib/api/client";
import type { Currency, Metadata, Money, Page, PageParams } from "@/lib/api/sdk/types";

/**
 * Payments SDK — typed stubs over contracts/openapi/v1/payments.yaml.
 * Paths: POST /payments, GET /payments, GET /payments/{id}, POST /payments/{id}/cancel.
 */

/** Payment rail / provider family (payments.yaml Rail). */
export type Rail = "honeycoin" | "mpesa" | "bank" | "on_chain";

/** Payment intent states (docs/STATE-MACHINES.md §1). */
export type PaymentState =
  | "CREATED"
  | "PENDING_PROVIDER"
  | "PROCESSING"
  | "SUCCEEDED"
  | "FAILED"
  | "EXPIRED"
  | "REVERSED"
  | "BLOCKED"
  | "CANCELLED";

/** What the caller must do next — V1 always `none` (server-side hand-off). */
export interface NextAction {
  type: "none";
}

/** A payment intent (payments.yaml Payment). */
export interface Payment {
  id: string;
  state: PaymentState;
  amount: Money;
  fee: Money;
  destination_wallet: string;
  rail: Rail;
  metadata?: Metadata;
  next_action: NextAction;
  failure_reason?: string;
  provider_ref?: string;
  expires_at: string;
  created_at: string;
  updated_at?: string;
}

export interface PaymentCreateRequest {
  amount_minor: number;
  currency: Currency;
  destination_wallet: string;
  rail?: Rail;
  metadata?: Metadata;
  expires_in_seconds?: number;
}

export interface PaymentListFilters extends PageParams {
  state?: PaymentState;
  /** Console-side filter (appended to the merged list-filters at integration). */
  rail?: Rail;
  principal_id?: string;
  created_from?: string;
  created_to?: string;
}

export interface PaymentList extends Page<Payment> {}

export async function listPayments(
  client: ApiClient,
  filters: PaymentListFilters = {},
): Promise<PaymentList> {
  return client.get<PaymentList>("/payments", {
    state: filters.state,
    principal_id: filters.principal_id,
    created_from: filters.created_from,
    created_to: filters.created_to,
    limit: filters.limit,
    cursor: filters.cursor,
  });
}

export async function getPayment(client: ApiClient, id: string): Promise<Payment> {
  return client.get<Payment>(`/payments/${encodeURIComponent(id)}`);
}

export async function createPayment(
  client: ApiClient,
  request: PaymentCreateRequest,
): Promise<Payment> {
  return client.post<Payment>("/payments", request);
}

export async function cancelPayment(client: ApiClient, id: string): Promise<Payment> {
  return client.post<Payment>(`/payments/${encodeURIComponent(id)}/cancel`);
}
