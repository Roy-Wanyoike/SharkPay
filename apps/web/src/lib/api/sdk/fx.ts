import type { ApiClient } from "@/lib/api/client";
import type { Currency, Money, Rate } from "@/lib/api/sdk/types";

/**
 * FX SDK — typed stubs over contracts/openapi/v1/fx.yaml.
 * Paths (v1.0.0): POST /fx/quotes, POST /fx/convert.
 * Quote/Conversion list types below are provisional console-side read
 * models — the merged contract currently defines only the two POST paths
 * (contracts are append-only per ADR 003 §2, so reads will be added there).
 */

/** Quote states (docs/STATE-MACHINES.md §4): QUOTED → LOCKED → EXECUTED | EXPIRED. */
export type QuoteState = "QUOTED" | "LOCKED" | "EXECUTED" | "EXPIRED";

export interface FxQuote {
  id: string;
  state: QuoteState;
  base_currency: Currency;
  quote_currency: Currency;
  source_amount: Money;
  target_amount: Money;
  rate: Rate;
  expires_at: string;
  created_at: string;
}

export interface QuoteCreateRequest {
  amount_minor: number;
  base_currency: Currency;
  quote_currency: Currency;
  expires_in_seconds?: number;
}

export interface Conversion {
  id: string;
  state: "EXECUTED";
  quote_id: string;
  source_wallet: string;
  destination_wallet: string;
  source_amount: Money;
  target_amount: Money;
  rate: Rate;
  /** Ledger journal entry id of the 4-leg conversion posting. */
  entry_id: string;
  created_at: string;
}

export interface ConversionCreateRequest {
  quote_id: string;
  source_wallet: string;
  destination_wallet: string;
}

export async function createQuote(
  client: ApiClient,
  request: QuoteCreateRequest,
): Promise<FxQuote> {
  return client.post<FxQuote>("/fx/quotes", request);
}

export async function convert(
  client: ApiClient,
  request: ConversionCreateRequest,
): Promise<Conversion> {
  return client.post<Conversion>("/fx/convert", request);
}
