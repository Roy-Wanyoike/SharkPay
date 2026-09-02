import type { ApiClient } from "@/lib/api/client";
import type { Currency, Money, Page, PageParams } from "@/lib/api/sdk/types";

/**
 * Wallets SDK — typed stubs over contracts/openapi/v1/wallets.yaml.
 * Paths: GET /wallets, GET /wallets/{id}, GET /wallets/{id}/statement.
 */

/** Wallet lifecycle (docs/STATE-MACHINES.md §5): active ⇄ frozen, active → closed. */
export type WalletStatus = "active" | "frozen" | "closed";

/**
 * Balance partitions. `available` = spendable now (never negative);
 * `pending` = in-flight incoming; `held` = reserved by in-flight ops.
 */
export interface WalletBalances {
  available: Money;
  pending: Money;
  held: Money;
}

export interface Wallet {
  id: string;
  principal_id: string;
  currency: Currency;
  status: WalletStatus;
  balances: WalletBalances;
  created_at: string;
  /** Present only when status is closed. */
  closed_at?: string;
}

export interface WalletListFilters extends PageParams {
  principal_id?: string;
  currency?: Currency;
  status?: WalletStatus;
}

export interface WalletList extends Page<Wallet> {}

/** Ledger journal entry types (docs/DATA-MODEL.md §3.1). */
export type EntryType = "capture" | "hold" | "release" | "reversal" | "fee" | "fx" | "adjustment";

/** Owning domain of the journal entry. */
export type LedgerSource = "payments" | "payouts" | "transfers" | "fx" | "fees" | "ops";

export interface StatementEntry {
  id: string;
  entry_id: string;
  entry_type: EntryType;
  direction: "debit" | "credit";
  amount: Money;
  balance_after: Money;
  source: LedgerSource;
  source_ref: string;
  reason?: string;
  created_at: string;
}

export interface StatementList extends Page<StatementEntry> {}

export async function listWallets(
  client: ApiClient,
  filters: WalletListFilters = {},
): Promise<WalletList> {
  return client.get<WalletList>("/wallets", {
    principal_id: filters.principal_id,
    currency: filters.currency,
    status: filters.status,
    limit: filters.limit,
    cursor: filters.cursor,
  });
}

export async function getWallet(client: ApiClient, id: string): Promise<Wallet> {
  return client.get<Wallet>(`/wallets/${encodeURIComponent(id)}`);
}

export async function getWalletStatement(
  client: ApiClient,
  id: string,
  params: PageParams = {},
): Promise<StatementList> {
  return client.get<StatementList>(`/wallets/${encodeURIComponent(id)}/statement`, {
    limit: params.limit,
    cursor: params.cursor,
  });
}
