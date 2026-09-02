import type { ApiClient } from "@/lib/api/client";
import type { Page, PageParams } from "@/lib/api/sdk/types";

/**
 * Webhooks SDK — typed stubs over contracts/openapi/v1/webhooks.yaml.
 * Paths: POST /webhook-endpoints, GET /webhook-endpoints, GET and DELETE
 * /webhook-endpoints/{id}.
 */

/** Webhook event catalog (webhooks.yaml EventName). */
export type WebhookEventName =
  | "payment.created"
  | "payment.pending_provider"
  | "payment.succeeded"
  | "payment.failed"
  | "payment.expired"
  | "payment.reversed"
  | "payout.created"
  | "payout.processing"
  | "payout.sent"
  | "payout.succeeded"
  | "payout.failed"
  | "payout.returned"
  | "transfer.succeeded"
  | "fx.quote.locked"
  | "fx.conversion.executed"
  | "wallet.balance.changed"
  | "risk.case.opened";

export interface WebhookEndpoint {
  id: string;
  url: string;
  events: WebhookEventName[];
  state: "active" | "dead";
  /** Redacted outside the creation response. */
  secret?: string;
  created_at: string;
  updated_at?: string;
}

export interface WebhookEndpointCreateRequest {
  url: string;
  events: WebhookEventName[];
  /** Shared secret for HMAC-SHA256 delivery signatures (16..256 chars). */
  secret: string;
}

export interface WebhookEndpointList extends Page<WebhookEndpoint> {}

export interface WebhookEndpointListFilters extends PageParams {
  state?: "active" | "dead";
}

/**
 * Delivery attempt — provisional console-side read model: webhooks.yaml
 * defines endpoints but no delivery log path yet (append-only contracts).
 */
export interface WebhookDelivery {
  id: string;
  endpoint_id: string;
  event: WebhookEventName;
  subject: string;
  response_status: number | null;
  attempts: number;
  state: "succeeded" | "retrying" | "dead";
  last_attempted_at: string;
}

export interface WebhookDeliveryList extends Page<WebhookDelivery> {}

export async function listWebhookEndpoints(
  client: ApiClient,
  filters: WebhookEndpointListFilters = {},
): Promise<WebhookEndpointList> {
  return client.get<WebhookEndpointList>("/webhook-endpoints", {
    state: filters.state,
    limit: filters.limit,
    cursor: filters.cursor,
  });
}

export async function getWebhookEndpoint(
  client: ApiClient,
  id: string,
): Promise<WebhookEndpoint> {
  return client.get<WebhookEndpoint>(`/webhook-endpoints/${encodeURIComponent(id)}`);
}

export async function createWebhookEndpoint(
  client: ApiClient,
  request: WebhookEndpointCreateRequest,
): Promise<WebhookEndpoint> {
  return client.post<WebhookEndpoint>("/webhook-endpoints", request);
}

export async function deleteWebhookEndpoint(client: ApiClient, id: string): Promise<void> {
  await client.request<void>({
    method: "DELETE",
    path: `/webhook-endpoints/${encodeURIComponent(id)}`,
    // DELETE is idempotent by nature; no key needed.
    idempotent: false,
  });
}
