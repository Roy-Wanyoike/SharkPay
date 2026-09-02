/**
 * Types projected from `contracts/openapi/v1/fx.yaml`.
 *
 * FX — TTL'd quotes and wallet-to-wallet conversions. Quote (TTL'd) →
 * convert (by quote id). Locking a quote consumes its TTL; conversion posts
 * the 4-leg journal entry (debit source wallet, credit FX position CCY1,
 * debit FX position CCY2, credit target wallet) so FX P&L is observable in
 * the ledger. Rates are integer minor units with an explicit exponent —
 * never floats. Required scope: `fx:read` for quotes, `fx:write` for
 * convert.
 */

import type { Currency, Money } from './common.js';
import type { WalletId } from './wallets.js';

/** FX quote id (`fxq_...`). */
export type QuoteId = string;

/** Pattern for `fxq_` ids (fx.yaml `Quote.id`). */
export const QUOTE_ID_PATTERN = /^fxq_[0-9A-Za-z]{20,}$/;

/** Type guard for {@link QuoteId}. */
export function isQuoteId(value: unknown): value is QuoteId {
  return typeof value === 'string' && QUOTE_ID_PATTERN.test(value);
}

/** FX conversion id (`cnv_...`). */
export type ConversionId = string;

/** Pattern for `cnv_` ids (fx.yaml `Conversion.id`). */
export const CONVERSION_ID_PATTERN = /^cnv_[0-9A-Za-z]{20,}$/;

/** Type guard for {@link ConversionId}. */
export function isConversionId(value: unknown): value is ConversionId {
  return typeof value === 'string' && CONVERSION_ID_PATTERN.test(value);
}

/**
 * Quote states (docs/STATE-MACHINES.md §4):
 * QUOTED → LOCKED → EXECUTED | EXPIRED. Expiry of a locked quote is a p1
 * incident (ops alert).
 */
export type QuoteState = 'QUOTED' | 'LOCKED' | 'EXECUTED' | 'EXPIRED';

/** Runtime list of {@link QuoteState} values. */
export const QUOTE_STATES: readonly QuoteState[] = ['QUOTED', 'LOCKED', 'EXECUTED', 'EXPIRED'];

/** Type guard for {@link QuoteState}. */
export function isQuoteState(value: unknown): value is QuoteState {
  return typeof value === 'string' && (QUOTE_STATES as readonly string[]).includes(value);
}

/**
 * Exchange rate as quote-currency minor units per one base-currency unit,
 * rendered at `exponent` fractional digits — an exact integer
 * representation (never a float). Example: value_minor 7719 with exponent 4
 * means 0.7719 USD per 1 KES.
 */
export interface Rate {
  /** `int64`, minimum 1. */
  value_minor: number;
  /** 0..18 fractional digits of the rate representation. */
  exponent: number;
  base_currency: Currency;
  quote_currency: Currency;
}

/** A TTL'd FX quote. */
export interface Quote {
  id: QuoteId;
  state: QuoteState;
  base_currency: Currency;
  quote_currency: Currency;
  source_amount: Money;
  /** Indicative converted amount at the quoted rate (includes mark-up policy). */
  target_amount: Money;
  rate: Rate;
  expires_at: string;
  created_at: string;
}

/** Request body for POST /fx/quotes (createQuote). */
export interface QuoteCreateRequest {
  /** `int64`, minimum 1. */
  amount_minor: number;
  base_currency: Currency;
  quote_currency: Currency;
  /** Quote TTL in seconds (5..3600, default 60). */
  expires_in_seconds?: number | undefined;
}

/**
 * An executed FX conversion (4-leg ledger entry). V1 converts
 * synchronously; additional states may be appended additively.
 */
export interface Conversion {
  id: ConversionId;
  state: 'EXECUTED';
  quote_id: QuoteId;
  /** Wallet holding the base currency (matches the quote's base currency). */
  source_wallet: WalletId;
  /** Wallet receiving the quote currency (matches the quote's quote currency). */
  destination_wallet: WalletId;
  source_amount: Money;
  target_amount: Money;
  rate: Rate;
  /** Ledger journal entry id of the 4-leg conversion posting (UUID). */
  entry_id: string;
  created_at: string;
}

/** Request body for POST /fx/convert (convert). */
export interface ConversionCreateRequest {
  quote_id: QuoteId;
  source_wallet: WalletId;
  destination_wallet: WalletId;
}
