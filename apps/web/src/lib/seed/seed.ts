import type { ApiKey, ApiKeyList } from "@/lib/api/sdk/apikeys";
import type { Conversion, FxQuote } from "@/lib/api/sdk/fx";
import type {
  Payment,
  PaymentList,
  PaymentState,
  Rail,
} from "@/lib/api/sdk/payments";
import type {
  Payout,
  PayoutList,
  PayoutState,
} from "@/lib/api/sdk/payouts";
import type { RiskCase, RiskCaseList } from "@/lib/api/sdk/risk";
import type { Wallet, WalletList } from "@/lib/api/sdk/wallets";
import type {
  WebhookDelivery,
  WebhookDeliveryList,
  WebhookEndpoint,
  WebhookEndpointList,
} from "@/lib/api/sdk/webhooks";

/**
 * ── DEMO SEED ────────────────────────────────────────────────────────────────
 * Synthetic, deterministic console data used ONLY while the live API is not
 * wired up (see src/lib/data/load.ts — every page prefers the real API and
 * falls back to this module on transport/API failure). No secrets, no real
 * customers; ids follow the contract patterns (pay_/pot_/wal_/fxq_/cnv_/
 * wh_) so the UI renders contract-shaped values. Delete once the read-side
 * API goes live.
 */

export const SEED_MARKER = "demo-seed" as const;

const PRINCIPAL_A = "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d";
const PRINCIPAL_B = "0192a7d5-8e4b-4c3a-8b7d-5f6a7b8c9e0f";

const kes = (amount_minor: number) => ({ amount_minor, currency: "KES" as const, exponent: 2 });
const usd = (amount_minor: number) => ({ amount_minor, currency: "USD" as const, exponent: 2 });
const usdc = (amount_minor: number) => ({ amount_minor, currency: "USDC" as const, exponent: 6 });
const eur = (amount_minor: number) => ({ amount_minor, currency: "EUR" as const, exponent: 2 });

export const seedPayments: Payment[] = [
  {
    id: "pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
    state: "SUCCEEDED",
    amount: kes(150000),
    fee: kes(750),
    destination_wallet: "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
    rail: "honeycoin",
    metadata: { order_id: "A-7731" },
    next_action: { type: "none" },
    provider_ref: "hc_88120039",
    expires_at: "2026-09-03T10:15:00Z",
    created_at: "2026-09-03T10:00:00Z",
    updated_at: "2026-09-03T10:04:41Z",
  },
  {
    id: "pay_01HZWS8F2J9R3P4Q6T8V0W1X2Y",
    state: "SUCCEEDED",
    amount: kes(8950000),
    fee: kes(44750),
    destination_wallet: "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
    rail: "mpesa",
    metadata: { order_id: "B-1120", channel: "till" },
    next_action: { type: "none" },
    provider_ref: "mp_9917234",
    expires_at: "2026-09-03T09:05:00Z",
    created_at: "2026-09-03T08:50:00Z",
    updated_at: "2026-09-03T08:52:13Z",
  },
  {
    id: "pay_01HZWT9G3K0S4Q5R7U9W1X2Y3Z",
    state: "PROCESSING",
    amount: usdc(250000000),
    fee: usdc(1250000),
    destination_wallet: "wal_01HZXTR2M4S6N8P0Q2R4T6V8W",
    rail: "on_chain",
    metadata: { network: "base" },
    next_action: { type: "none" },
    provider_ref: "0x9f2a…c81d",
    expires_at: "2026-09-03T12:30:00Z",
    created_at: "2026-09-03T11:45:00Z",
  },
  {
    id: "pay_01HZWU0H4L1T5R6S8V0W2X3Y4A",
    state: "PENDING_PROVIDER",
    amount: kes(2450000),
    fee: kes(12250),
    destination_wallet: "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
    rail: "honeycoin",
    metadata: { order_id: "A-7732" },
    next_action: { type: "none" },
    expires_at: "2026-09-03T12:15:00Z",
    created_at: "2026-09-03T11:55:00Z",
  },
  {
    id: "pay_01HZWV1I5M2U6S7T9W1X3Y4Z5B",
    state: "PENDING_PROVIDER",
    amount: eur(1800000),
    fee: eur(9000),
    destination_wallet: "wal_01HZYUR4N6S8P0Q2R4T6V8X0",
    rail: "bank",
    next_action: { type: "none" },
    expires_at: "2026-09-03T13:00:00Z",
    created_at: "2026-09-03T12:10:00Z",
  },
  {
    id: "pay_01HZWW2J6N3V7T8U0W2X4Y5Z6C",
    state: "SUCCEEDED",
    amount: usd(4200000),
    fee: usd(21000),
    destination_wallet: "wal_01HZYUR4N6S8P0Q2R4T6V8X0",
    rail: "bank",
    metadata: { order_id: "C-0090" },
    next_action: { type: "none" },
    provider_ref: "wb_44100212",
    expires_at: "2026-09-02T18:00:00Z",
    created_at: "2026-09-02T17:30:00Z",
    updated_at: "2026-09-02T17:58:02Z",
  },
  {
    id: "pay_01HZWX3K7O4W8U9V1W3X5Y6Z7D",
    state: "FAILED",
    amount: kes(990000),
    fee: kes(4950),
    destination_wallet: "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
    rail: "mpesa",
    next_action: { type: "none" },
    failure_reason: "Provider rejected: subscriber unreachable (SBI timeout).",
    expires_at: "2026-09-02T16:00:00Z",
    created_at: "2026-09-02T15:30:00Z",
    updated_at: "2026-09-02T15:44:19Z",
  },
  {
    id: "pay_01HZWY4L8P5X9V0W2X4Y6Z7A8E",
    state: "BLOCKED",
    amount: kes(45000000),
    fee: kes(225000),
    destination_wallet: "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
    rail: "honeycoin",
    next_action: { type: "none" },
    expires_at: "2026-09-02T14:20:00Z",
    created_at: "2026-09-02T14:05:00Z",
  },
  {
    id: "pay_01HZWZ5M9Q6Y0W1X3Y5Z7A8B9F",
    state: "EXPIRED",
    amount: kes(300000),
    fee: kes(1500),
    destination_wallet: "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
    rail: "honeycoin",
    next_action: { type: "none" },
    expires_at: "2026-09-02T11:15:00Z",
    created_at: "2026-09-02T11:00:00Z",
  },
  {
    id: "pay_01HJ0104N7R7Z1X2Y4Z6A8B0C1G",
    state: "CANCELLED",
    amount: usdc(180000000),
    fee: usdc(900000),
    destination_wallet: "wal_01HZXTR2M4S6N8P0Q2R4T6V8W",
    rail: "on_chain",
    next_action: { type: "none" },
    expires_at: "2026-09-01T09:45:00Z",
    created_at: "2026-09-01T09:30:00Z",
  },
  {
    id: "pay_01HJ0115O8S8A2Y3Z5A7B9C1D2H",
    state: "SUCCEEDED",
    amount: kes(12500000),
    fee: kes(62500),
    destination_wallet: "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
    rail: "mpesa",
    metadata: { order_id: "B-1102" },
    next_action: { type: "none" },
    provider_ref: "mp_9908142",
    expires_at: "2026-09-01T08:20:00Z",
    created_at: "2026-09-01T08:05:00Z",
    updated_at: "2026-09-01T08:07:44Z",
  },
  {
    id: "pay_01HJ0126P9T9B3Z4A6B8C0D2E3I",
    state: "SUCCEEDED",
    amount: kes(5300000),
    fee: kes(26500),
    destination_wallet: "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
    rail: "honeycoin",
    metadata: { order_id: "A-7730" },
    next_action: { type: "none" },
    provider_ref: "hc_88119733",
    expires_at: "2026-09-01T07:00:00Z",
    created_at: "2026-09-01T06:45:00Z",
    updated_at: "2026-09-01T06:51:09Z",
  },
];

export function seedPaymentsPage(filters: { state?: string; rail?: string; limit?: number } = {}): PaymentList {
  const items = seedPayments.filter(
    (payment) =>
      (!filters.state || payment.state === filters.state) &&
      (!filters.rail || payment.rail === filters.rail),
  );
  const limit = filters.limit ?? items.length;
  return { items: items.slice(0, limit), next_cursor: null };
}

export const seedPayouts: Payout[] = [
  {
    id: "pot_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
    state: "SUCCEEDED",
    source_wallet: "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
    amount: kes(500000),
    fee: kes(11000),
    destination: { type: "mpesa", msisdn: "+254712345678" },
    rail: "mpesa",
    provider_ref: "mp_9112288",
    created_at: "2026-09-03T09:12:00Z",
    updated_at: "2026-09-03T09:14:52Z",
  },
  {
    id: "pot_01HZWS8F2J9R3P4Q6T8V0W1X2Y",
    state: "SENT",
    source_wallet: "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
    amount: kes(12500000),
    fee: kes(275000),
    destination: {
      type: "bank",
      bank_code: "KCB",
      account_number: "1122334455",
      account_name: "Bluewave Traders Ltd",
      country: "KE",
    },
    rail: "bank",
    provider_ref: "wb_44100507",
    created_at: "2026-09-03T08:40:00Z",
  },
  {
    id: "pot_01HZWT9G3K0S4Q5R7U9W1X2Y3Z",
    state: "PROCESSING",
    source_wallet: "wal_01HZXTR2M4S6N8P0Q2R4T6V8W",
    amount: usdc(12500000),
    fee: usdc(31250),
    destination: { type: "on_chain", network: "base", address: "0x8f2c4a19d73b5e6f8092ab1c3d4e5f678901a2b3" },
    rail: "on_chain",
    created_at: "2026-09-03T07:55:00Z",
  },
  {
    id: "pot_01HZWU0H4L1T5R6S8V0W2X3Y4A",
    state: "PENDING_RISK",
    source_wallet: "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
    amount: kes(98000000),
    fee: kes(2156000),
    destination: { type: "bank", bank_code: "EQ", account_number: "9988776655", country: "KE" },
    rail: "bank",
    created_at: "2026-09-03T07:02:00Z",
  },
  {
    id: "pot_01HZWV1I5M2U6S7T9W1X3Y4Z5B",
    state: "RETURNED",
    source_wallet: "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
    amount: kes(750000),
    fee: kes(16500),
    destination: { type: "mpesa", msisdn: "+254733221100" },
    rail: "mpesa",
    return_reason: "Beneficiary wallet limit exceeded; non-refundable rail fee kept.",
    provider_ref: "mp_9104411",
    created_at: "2026-09-02T16:20:00Z",
    updated_at: "2026-09-02T18:03:00Z",
  },
  {
    id: "pot_01HZWW2J6N3V7T8U0W2X4Y5Z6C",
    state: "FAILED",
    source_wallet: "wal_01HZYUR4N6S8P0Q2R4T6V8X0",
    amount: eur(250000),
    fee: eur(5500),
    destination: { type: "bank", bank_code: "DB", account_number: "DE445001051754073249", country: "DE" },
    rail: "bank",
    failure_reason: "Correspondent bank rejected: invalid beneficiary name (AMLD check).",
    created_at: "2026-09-02T11:30:00Z",
  },
  {
    id: "pot_01HZWX3K7O4W8U9V1W3X5Y6Z7D",
    state: "SUCCEEDED",
    source_wallet: "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
    amount: kes(20000000),
    fee: kes(440000),
    destination: { type: "mpesa", msisdn: "+254700112233" },
    rail: "mpesa",
    provider_ref: "mp_9099871",
    created_at: "2026-09-01T15:10:00Z",
    updated_at: "2026-09-01T15:12:38Z",
  },
  {
    id: "pot_01HZWY4L8P5X9V0W2X4Y6Z7A8E",
    state: "BLOCKED",
    source_wallet: "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
    amount: kes(185000000),
    fee: kes(4070000),
    destination: { type: "bank", bank_code: "KCB", account_number: "5566778899", country: "KE" },
    rail: "bank",
    created_at: "2026-09-01T10:44:00Z",
  },
  {
    id: "pot_01HZWZ5M9Q6Y0W1X3Y5Z7A8B9F",
    state: "CANCELLED",
    source_wallet: "wal_01HZXTR2M4S6N8P0Q2R4T6V8W",
    amount: usdc(40000000),
    fee: usdc(100000),
    destination: { type: "on_chain", network: "polygon", address: "0x1a2b3c4d5e6f708192a3b4c5d6e7f8091a2b3c4d" },
    rail: "on_chain",
    created_at: "2026-09-01T09:05:00Z",
  },
];

export function seedPayoutsPage(filters: { state?: string; rail?: string; limit?: number } = {}): PayoutList {
  const items = seedPayouts.filter(
    (payout) =>
      (!filters.state || payout.state === filters.state) &&
      (!filters.rail || payout.rail === filters.rail),
  );
  const limit = filters.limit ?? items.length;
  return { items: items.slice(0, limit), next_cursor: null };
}

export const seedWallets: Wallet[] = [
  {
    id: "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
    principal_id: PRINCIPAL_A,
    currency: "KES",
    status: "active",
    balances: { available: kes(12500000), pending: kes(2450000), held: kes(500000) },
    created_at: "2026-08-30T09:00:00Z",
  },
  {
    id: "wal_01HZXTR2M4S6N8P0Q2R4T6V8W",
    principal_id: PRINCIPAL_A,
    currency: "USDC",
    status: "active",
    balances: { available: usdc(1254000000), pending: usdc(250000000), held: usdc(0) },
    created_at: "2026-08-30T09:01:00Z",
  },
  {
    id: "wal_01HZYUR4N6S8P0Q2R4T6V8X0",
    principal_id: PRINCIPAL_B,
    currency: "USD",
    status: "active",
    balances: { available: usd(180000000), pending: usd(0), held: usd(4200000) },
    created_at: "2026-08-31T14:20:00Z",
  },
  {
    id: "wal_01HJZ1S5P7T9U1V2W4X6Y8Z0A1",
    principal_id: PRINCIPAL_B,
    currency: "EUR",
    status: "active",
    balances: { available: eur(4200000), pending: eur(1800000), held: eur(0) },
    created_at: "2026-08-31T14:22:00Z",
  },
  {
    id: "wal_01HJZ2T6Q8U0V2W3X5Y7Z9A1B2",
    principal_id: PRINCIPAL_A,
    currency: "KES",
    status: "frozen",
    balances: { available: kes(0), pending: kes(0), held: kes(90000000) },
    created_at: "2026-08-30T09:02:00Z",
  },
  {
    id: "wal_01HJZ3U7R9V1W3X4Y6Z8A0B2C3",
    principal_id: PRINCIPAL_B,
    currency: "USDT",
    status: "active",
    balances: { available: usdc(310000000), pending: usdc(0), held: usdc(12500000) },
    created_at: "2026-09-01T08:00:00Z",
  },
  {
    id: "wal_01HJZ4V8S0W2X4Y5Z7A9B1C3D4",
    principal_id: PRINCIPAL_A,
    currency: "GBP",
    status: "active",
    balances: { available: { amount_minor: 250000, currency: "GBP", exponent: 2 }, pending: { amount_minor: 0, currency: "GBP", exponent: 2 }, held: { amount_minor: 0, currency: "GBP", exponent: 2 } },
    created_at: "2026-09-01T08:05:00Z",
  },
  {
    id: "wal_01HJZ5W9T1X3Y5Z6A8B0C2D4E5",
    principal_id: PRINCIPAL_B,
    currency: "KES",
    status: "closed",
    balances: { available: kes(0), pending: kes(0), held: kes(0) },
    created_at: "2026-08-30T10:00:00Z",
    closed_at: "2026-09-01T12:00:00Z",
  },
];

export function seedWalletsPage(filters: { currency?: string; status?: string; limit?: number } = {}): WalletList {
  const items = seedWallets.filter(
    (wallet) =>
      (!filters.currency || wallet.currency === filters.currency) &&
      (!filters.status || wallet.status === filters.status),
  );
  const limit = filters.limit ?? items.length;
  return { items: items.slice(0, limit), next_cursor: null };
}

export const seedQuotes: FxQuote[] = [
  {
    id: "fxq_01HZWQ1R3T5V7X9A2C4E6G8I0K",
    state: "QUOTED",
    base_currency: "KES",
    quote_currency: "USD",
    source_amount: kes(15000000),
    target_amount: usd(1157850),
    rate: { value_minor: 7719, exponent: 4, base_currency: "KES", quote_currency: "USD" },
    expires_at: "2026-09-03T12:40:00Z",
    created_at: "2026-09-03T12:25:00Z",
  },
  {
    id: "fxq_01HZWR2S4U6V8W0B1D3E5F7G9H",
    state: "LOCKED",
    base_currency: "KES",
    quote_currency: "USDC",
    source_amount: kes(80000000),
    target_amount: usdc(6175200000),
    rate: { value_minor: 7719, exponent: 4, base_currency: "KES", quote_currency: "USDC" },
    expires_at: "2026-09-03T12:35:00Z",
    created_at: "2026-09-03T12:05:00Z",
  },
  {
    id: "fxq_01HZWS3T5V7X9Y1C2E4F6G8H9I",
    state: "EXECUTED",
    base_currency: "USD",
    quote_currency: "KES",
    source_amount: usd(4200000),
    target_amount: kes(544020000),
    rate: { value_minor: 1295286, exponent: 4, base_currency: "USD", quote_currency: "KES" },
    expires_at: "2026-09-02T18:00:00Z",
    created_at: "2026-09-02T17:55:00Z",
  },
  {
    id: "fxq_01HZWT4U6V8W0X2D3E5G7H9I0J",
    state: "EXPIRED",
    base_currency: "EUR",
    quote_currency: "USD",
    source_amount: eur(1000000),
    target_amount: usd(1085000),
    rate: { value_minor: 10850, exponent: 4, base_currency: "EUR", quote_currency: "USD" },
    expires_at: "2026-09-02T12:10:00Z",
    created_at: "2026-09-02T12:05:00Z",
  },
  {
    id: "fxq_01HZWU5V7X9Y1Z3E4F6H8I0J1K",
    state: "QUOTED",
    base_currency: "USDC",
    quote_currency: "KES",
    source_amount: usdc(100000000),
    target_amount: kes(129730000),
    rate: { value_minor: 129730, exponent: 4, base_currency: "USDC", quote_currency: "KES" },
    expires_at: "2026-09-03T13:02:00Z",
    created_at: "2026-09-03T12:47:00Z",
  },
];

export const seedConversions: Conversion[] = [
  {
    id: "cnv_01HZWQ1R3T5V7X9A2C4E6G8I0K",
    state: "EXECUTED",
    quote_id: "fxq_01HZWQ0Q2S4U6W8Y0A1C3E5G7I",
    source_wallet: "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
    destination_wallet: "wal_01HZYUR4N6S8P0Q2R4T6V8X0",
    source_amount: kes(30000000),
    target_amount: usd(2315700),
    rate: { value_minor: 7719, exponent: 4, base_currency: "KES", quote_currency: "USD" },
    entry_id: "5e9c1a2b-8c4d-4e6f-9a0b-1c2d3e4f5a6b",
    created_at: "2026-09-03T09:30:00Z",
  },
  {
    id: "cnv_01HZWR2S4U6V8W0B1D3E5F7G9H",
    state: "EXECUTED",
    quote_id: "fxq_01HZWR1R3T5U7V9X1B2D4F6H8J",
    source_wallet: "wal_01HZYUR4N6S8P0Q2R4T6V8X0",
    destination_wallet: "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
    source_amount: usd(5000000),
    target_amount: kes(647640000),
    rate: { value_minor: 1295286, exponent: 4, base_currency: "USD", quote_currency: "KES" },
    entry_id: "6f0d2b3c-9d5e-4f7a-8b1c-2d3e4f5a6b7c",
    created_at: "2026-09-03T08:12:00Z",
  },
  {
    id: "cnv_01HZWS3T5V7X9Y1C2E4F6G8H9I",
    state: "EXECUTED",
    quote_id: "fxq_01HZWS2S4U6T7V8W0X1Y2A3B4C5",
    source_wallet: "wal_01HZXTR2M4S6N8P0Q2R4T6V8W",
    destination_wallet: "wal_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
    source_amount: usdc(150000000),
    target_amount: kes(194520000),
    rate: { value_minor: 129680, exponent: 4, base_currency: "USDC", quote_currency: "KES" },
    entry_id: "7a1e3c4d-0e6f-4a8b-9c2d-3e4f5a6b7c8d",
    created_at: "2026-09-02T16:40:00Z",
  },
  {
    id: "cnv_01HZWT4U6V8W0X2D3E5G7H9I0J",
    state: "EXECUTED",
    quote_id: "fxq_01HZWT3T5U7V6W8X9Y0Z1A2B3C4",
    source_wallet: "wal_01HZYUR4N6S8P0Q2R4T6V8X0",
    destination_wallet: "wal_01HJZ1S5P7T9U1V2W4X6Y8Z0A1",
    source_amount: usd(1000000),
    target_amount: eur(922000),
    rate: { value_minor: 92200, exponent: 4, base_currency: "USD", quote_currency: "EUR" },
    entry_id: "8b2f4d5e-1f7a-4b9c-0d3e-4f5a6b7c8d9e",
    created_at: "2026-09-02T11:05:00Z",
  },
];

export const seedRiskCases: RiskCase[] = [
  {
    id: "rc_01HZWO0P2R4T6V8X0A2C4E6G8I",
    state: "OPEN",
    severity: "high",
    rule: "velocity_per_hour",
    subject_ref: "pay_01HZWY4L8P5X9V0W2X4Y6Z7A8E",
    amount: kes(45000000),
    assignee: null,
    opened_at: "2026-09-03T12:02:00Z",
  },
  {
    id: "rc_01HZWP1Q3S5U7W9Y1B3D5F7H9J",
    state: "INVESTIGATING",
    severity: "critical",
    rule: "structuring_pattern",
    subject_ref: "pot_01HZWU0H4L1T5R6S8V0W2X3Y4A",
    amount: kes(98000000),
    assignee: "Amina Okonkwo",
    opened_at: "2026-09-03T07:05:00Z",
    updated_at: "2026-09-03T10:30:00Z",
  },
  {
    id: "rc_01HZWQ2R4T6V8X0A2C4E6G8I0K",
    state: "OPEN",
    severity: "medium",
    rule: "kyc_mismatch",
    subject_ref: "pot_01HZWV1I5M2U6S7T9W1X3Y4Z5B",
    amount: kes(750000),
    assignee: "David Mwangi",
    opened_at: "2026-09-02T18:10:00Z",
  },
  {
    id: "rc_01HZWR3S5U7V9W1B3D5F7G9H1L",
    state: "RESOLVED",
    severity: "low",
    rule: "geo_anomaly",
    subject_ref: "pay_01HZWW2J6N3V7T8U0W2X4Y5Z6C",
    amount: usd(4200000),
    assignee: "Amina Okonkwo",
    opened_at: "2026-09-02T17:35:00Z",
    resolved_at: "2026-09-02T19:00:00Z",
    resolution_note: "Verified corporate travel; no action.",
  },
  {
    id: "rc_01HZWS4T6V8W0X2C4E5G7H9I2M",
    state: "OPEN",
    severity: "high",
    rule: "sanctions_screening_hit",
    subject_ref: "pot_01HZWX3K7O4W8U9V1W3X5Y6Z7D",
    amount: kes(185000000),
    assignee: null,
    opened_at: "2026-09-01T10:46:00Z",
  },
  {
    id: "rc_01HZWT5U7X9Y1Z3D4F6H8I0J3N",
    state: "DISMISSED",
    severity: "low",
    rule: "duplicate_payment_attempt",
    subject_ref: "pay_01HZWZ5M9Q6Y0W1X3Y5Z7A8B9F",
    amount: kes(300000),
    assignee: "David Mwangi",
    opened_at: "2026-09-01T11:20:00Z",
    resolved_at: "2026-09-01T11:45:00Z",
    resolution_note: "Client retry of a cancelled intent; dismissed.",
  },
];

export function seedRiskCasesPage(filters: { state?: string; limit?: number } = {}): RiskCaseList {
  const items = seedRiskCases.filter((riskCase) => !filters.state || riskCase.state === filters.state);
  const limit = filters.limit ?? items.length;
  return { items: items.slice(0, limit), next_cursor: null };
}

export const seedApiKeys: ApiKey[] = [
  {
    id: "key_01HZWNA1C3E5G7I9K1M3O5Q7S",
    name: "Ledger automation (prod)",
    masked_key: "sp_live_a91f…",
    environment: "live",
    scopes: ["payments:read", "payments:write", "wallets:read"],
    state: "active",
    created_at: "2026-08-28T10:00:00Z",
    last_used_at: "2026-09-03T11:58:00Z",
  },
  {
    id: "key_01HZWNB2D4F6H8J0L2N4P6R8T",
    name: "Ops console reports",
    masked_key: "sp_live_5b2e…",
    environment: "live",
    scopes: ["wallets:read", "fx:read"],
    state: "active",
    created_at: "2026-08-29T14:30:00Z",
    last_used_at: "2026-09-03T08:12:00Z",
  },
  {
    id: "key_01HZWNC3E5G7I9K1M3O5Q7S9U",
    name: "CI contract tests",
    masked_key: "sp_test_c77d…",
    environment: "test",
    scopes: ["payments:read", "payouts:read", "webhooks:manage"],
    state: "active",
    created_at: "2026-09-01T09:15:00Z",
    last_used_at: "2026-09-03T06:40:00Z",
  },
  {
    id: "key_01HZWND4F6H8J0L2N4P6R8T0V",
    name: "Legacy storefront",
    masked_key: "sp_live_e3aa…",
    environment: "live",
    scopes: ["payments:write"],
    state: "revoked",
    created_at: "2026-08-20T11:00:00Z",
    last_used_at: "2026-08-31T19:03:00Z",
  },
  {
    id: "key_01HZWNE5G7I9K1M3O5Q7S9U1W",
    name: "Sandbox probe",
    masked_key: "sp_test_2f4c…",
    environment: "test",
    scopes: ["risk:read"],
    state: "active",
    created_at: "2026-09-02T13:45:00Z",
    last_used_at: null,
  },
];

export function seedApiKeysPage(filters: { state?: string; limit?: number } = {}): ApiKeyList {
  const items = seedApiKeys.filter((apiKey) => !filters.state || apiKey.state === filters.state);
  const limit = filters.limit ?? items.length;
  return { items: items.slice(0, limit), next_cursor: null };
}

export const seedWebhookEndpoints: WebhookEndpoint[] = [
  {
    id: "wh_01HZWLA1C3E5G7I9K1M3O5Q7S",
    url: "https://ops.bluewave.example.com/hooks/sharkpay",
    events: ["payment.created", "payment.succeeded", "payment.failed", "payment.reversed"],
    state: "active",
    secret: "whsec_••••••••",
    created_at: "2026-08-28T09:00:00Z",
    updated_at: "2026-09-02T07:00:00Z",
  },
  {
    id: "wh_01HZWLB2D4F6H8J0L2N4P6R8T",
    url: "https://billing.bluewave.example.com/sharkpay/events",
    events: ["payout.succeeded", "payout.returned", "payout.failed"],
    state: "active",
    secret: "whsec_••••••••",
    created_at: "2026-08-29T12:30:00Z",
  },
  {
    id: "wh_01HZWLC3E5G7I9K1M3O5Q7S9U",
    url: "https://ledger-sync.internal.example.com/v1/events",
    events: ["wallet.balance.changed", "fx.conversion.executed", "risk.case.opened"],
    state: "active",
    secret: "whsec_••••••••",
    created_at: "2026-08-30T16:20:00Z",
  },
  {
    id: "wh_01HZWLD4F6H8J0L2N4P6R8T0V",
    url: "https://old-partner.example.com/sharkpay",
    events: ["payment.succeeded"],
    state: "dead",
    secret: "whsec_••••••••",
    created_at: "2026-08-01T10:00:00Z",
    updated_at: "2026-09-01T22:40:00Z",
  },
];

export function seedWebhookEndpointsPage(filters: { state?: string; limit?: number } = {}): WebhookEndpointList {
  const items = seedWebhookEndpoints.filter((hook) => !filters.state || hook.state === filters.state);
  const limit = filters.limit ?? items.length;
  return { items: items.slice(0, limit), next_cursor: null };
}

export const seedWebhookDeliveries: WebhookDelivery[] = [
  {
    id: "del_01HZWM6F8H0J2L4N6P8R0T2V4",
    endpoint_id: "wh_01HZWLA1C3E5G7I9K1M3O5Q7S",
    event: "payment.succeeded",
    subject: "pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
    response_status: 200,
    attempts: 1,
    state: "succeeded",
    last_attempted_at: "2026-09-03T10:04:45Z",
  },
  {
    id: "del_01HZWM7G9I1K3M5N7P9R1T3V5",
    endpoint_id: "wh_01HZWLB2D4F6H8J0L2N4P6R8T",
    event: "payout.succeeded",
    subject: "pot_01HZWR4Z7K8Q2N5M9X3V1B6Y0A",
    response_status: 200,
    attempts: 1,
    state: "succeeded",
    last_attempted_at: "2026-09-03T09:15:02Z",
  },
  {
    id: "del_01HZWM8H0J2L4O6P8Q0R2T4V6",
    endpoint_id: "wh_01HZWLA1C3E5G7I9K1M3O5Q7S",
    event: "payment.failed",
    subject: "pay_01HZWX3K7O4W8U9V1W3X5Y6Z7D",
    response_status: 500,
    attempts: 3,
    state: "retrying",
    last_attempted_at: "2026-09-02T15:46:30Z",
  },
  {
    id: "del_01HZWM9I1K3M5P6Q7R8S9T0U1",
    endpoint_id: "wh_01HZWLD4F6H8J0L2N4P6R8T0V",
    event: "payment.succeeded",
    subject: "pay_01HJ0115O8S8A2Y3Z5A7B9C1D2H",
    response_status: 401,
    attempts: 24,
    state: "dead",
    last_attempted_at: "2026-09-01T22:40:00Z",
  },
  {
    id: "del_01HZWNAJ2L4N6Q7R8S9T0U1V2",
    endpoint_id: "wh_01HZWLC3E5G7I9K1M3O5Q7S9U",
    event: "fx.conversion.executed",
    subject: "cnv_01HZWR2S4U6V8W0B1D3E5F7G9H",
    response_status: 200,
    attempts: 1,
    state: "succeeded",
    last_attempted_at: "2026-09-03T08:12:11Z",
  },
  {
    id: "del_01HZWNBK3M5O7R8S9T0U1V2W3",
    endpoint_id: "wh_01HZWLC3E5G7I9K1M3O5Q7S9U",
    event: "risk.case.opened",
    subject: "rc_01HZWO0P2R4T6V8X0A2C4E6G8I",
    response_status: 503,
    attempts: 2,
    state: "retrying",
    last_attempted_at: "2026-09-03T12:03:40Z",
  },
  {
    id: "del_01HZWNCL4N6P8S9T0U1V2W3X4",
    endpoint_id: "wh_01HZWLB2D4F6H8J0L2N4P6R8T",
    event: "payout.returned",
    subject: "pot_01HZWV1I5M2U6S7T9W1X3Y4Z5B",
    response_status: 200,
    attempts: 1,
    state: "succeeded",
    last_attempted_at: "2026-09-02T18:03:15Z",
  },
];

export interface DashboardMetrics {
  paymentsToday: { count: number; volume: { amount_minor: number; currency: "KES"; exponent: 2 }; deltaPct: number };
  successRatePct: number;
  successRateDeltaPts: number;
  activeBreaks: number;
  breaksTrend: number;
  webhookHealth: {
    deliverySuccessPct: number;
    deadEndpoints: number;
    pendingRetries: number;
  };
}

export const seedDashboardMetrics: DashboardMetrics = {
  paymentsToday: { count: 1284, volume: { amount_minor: 456230000, currency: "KES", exponent: 2 }, deltaPct: 12.4 },
  successRatePct: 98.4,
  successRateDeltaPts: 1.2,
  activeBreaks: 3,
  breaksTrend: -2,
  webhookHealth: { deliverySuccessPct: 96.2, deadEndpoints: 1, pendingRetries: 12 },
};
