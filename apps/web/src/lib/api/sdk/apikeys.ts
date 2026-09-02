import type { ApiClient } from "@/lib/api/client";
import type { Page, PageParams } from "@/lib/api/sdk/types";

/**
 * API keys SDK — typed stubs.
 *
 * NOTE: there is NO merged api-keys OpenAPI contract yet. These types follow
 * the common.yaml conventions and the api-gateway's key model
 * (docs/API-CONTRACTS.md §5 — scoped keys, `sp_live_`/`sp_test_` prefixes
 * after the PR #15 prefix incident) and are provisional until the contract
 * lands. Full key material is write-only: creation returns it once; lists
 * only ever see the masked prefix.
 */

export interface ApiKey {
  id: string;
  name: string;
  /** Masked prefix, e.g. sp_live_a91f… — never the full secret. */
  masked_key: string;
  environment: "test" | "live";
  scopes: string[];
  state: "active" | "revoked";
  created_at: string;
  last_used_at: string | null;
}

export interface ApiKeyListFilters extends PageParams {
  state?: "active" | "revoked";
  environment?: "test" | "live";
}

export interface ApiKeyList extends Page<ApiKey> {}

export interface ApiKeyCreateRequest {
  name: string;
  environment: "test" | "live";
  scopes: string[];
}

/** Returned exactly once at creation — never again. */
export interface ApiKeyCreateResult extends ApiKey {
  secret: string;
}

export async function listApiKeys(
  client: ApiClient,
  filters: ApiKeyListFilters = {},
): Promise<ApiKeyList> {
  return client.get<ApiKeyList>("/api-keys", {
    state: filters.state,
    environment: filters.environment,
    limit: filters.limit,
    cursor: filters.cursor,
  });
}

export async function createApiKey(
  client: ApiClient,
  request: ApiKeyCreateRequest,
): Promise<ApiKeyCreateResult> {
  return client.post<ApiKeyCreateResult>("/api-keys", request);
}

export async function revokeApiKey(client: ApiClient, id: string): Promise<ApiKey> {
  return client.post<ApiKey>(`/api-keys/${encodeURIComponent(id)}/revoke`);
}
