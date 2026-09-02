/**
 * @sharkpay/client — typed TypeScript SDK for the SharkPay v1 API.
 *
 * Hand-crafted (no codegen, zero runtime dependencies) as a faithful
 * projection of contracts/openapi/v1/*.yaml:
 *
 * - Types: `src/types/*` (one module per contract).
 * - Client: {@link SharkPayClient} with per-resource methods, automatic
 *   `Idempotency-Key` generation, bounded 5xx retry with backoff+jitter,
 *   timeouts, and the typed error hierarchy.
 * - Webhooks: `CloudEvent` envelope + payload unions (`src/events.ts`) and
 *   HMAC signature verification (`src/webhook-signature.ts`).
 * - Money: safe runtime validation and exact bigint parsing (`src/money.ts`).
 */

export * from './types/common.js';
export * from './types/wallets.js';
export * from './types/payments.js';
export * from './types/payouts.js';
export * from './types/transfers.js';
export * from './types/fx.js';
export * from './types/webhooks.js';
export * from './errors.js';
export * from './money.js';
export * from './events.js';
export * from './webhook-signature.js';
export * from './client.js';
