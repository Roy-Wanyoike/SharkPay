import type { ApiClient } from "@/lib/api/client";
import type { Money, Page, PageParams } from "@/lib/api/sdk/types";

/**
 * Risk cases SDK — typed stubs.
 *
 * NOTE: there is NO merged risk-cases OpenAPI contract yet (the risk service
 * exists, contracts/openapi/v1 has no risk.yaml). These types follow the
 * common.yaml conventions (money shape, cursor pagination, `rc_`-prefixed
 * ids used by the risk.case.opened webhook event) and are provisional until
 * the contract lands — reconcile at integration time (ADR 003 §2).
 */

export type RiskCaseState = "OPEN" | "INVESTIGATING" | "RESOLVED" | "DISMISSED";

export type RiskCaseSeverity = "low" | "medium" | "high" | "critical";

export interface RiskCase {
  id: string;
  state: RiskCaseState;
  severity: RiskCaseSeverity;
  /** Rule that fired, e.g. velocity_per_hour, structuring_pattern. */
  rule: string;
  /** The business object under review (payment/payout id). */
  subject_ref: string;
  amount: Money;
  assignee: string | null;
  opened_at: string;
  updated_at?: string;
  resolved_at?: string;
  resolution_note?: string;
}

export interface RiskCaseListFilters extends PageParams {
  state?: RiskCaseState;
  severity?: RiskCaseSeverity;
}

export interface RiskCaseList extends Page<RiskCase> {}

export async function listRiskCases(
  client: ApiClient,
  filters: RiskCaseListFilters = {},
): Promise<RiskCaseList> {
  return client.get<RiskCaseList>("/risk/cases", {
    state: filters.state,
    severity: filters.severity,
    limit: filters.limit,
    cursor: filters.cursor,
  });
}

export async function getRiskCase(client: ApiClient, id: string): Promise<RiskCase> {
  return client.get<RiskCase>(`/risk/cases/${encodeURIComponent(id)}`);
}
