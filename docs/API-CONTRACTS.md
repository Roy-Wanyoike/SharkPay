# SharkPay Core — API Contracts (`/v1`)

| | |
|---|---|
| **Companion to** | [PRD](PRD.md) · [Architecture](ARCHITECTURE.md) · [State Machines](STATE-MACHINES.md) |
| **Source of truth for shapes** | `contracts/openapi/v1/*.yaml` (OpenAPI 3.1) — this doc is the normative summary |

---

## 1. Principles

1. **REST over HTTPS**, JSON bodies, base path `/v1`.
2. **Idempotency required** on all state-changing POSTs: header
   `Idempotency-Key: <uuid>`; scope = `(api key, endpoint, key)`; duplicates return the
   original response with `X-Idempotent-Replay: true`.
3. **Auth:** `Authorization: Bearer <api key>`; keys are scoped (§5); agents additionally
   policy-bound (see [Security](SECURITY.md)).
4. **Errors** are a single envelope, always:

```json
{
  "error": {
    "code": "insufficient_funds",
    "message": "Wallet balance after holds is 1200, requested 5000.",
    "request_id": "req_01H...",
    "details": { "available_minor": 1200, "requested_minor": 5000 }
  }
}
```

5. **Pagination:** cursor-based (`?limit=50&cursor=...`), max limit 100.
6. **Money:** always `{ "amount_minor": 1500, "currency": "KES", "exponent": 2 }`.

## 2. Payments

| Method & path | Purpose |
|---|---|
| `POST /v1/payments` | Create payment intent |
| `GET /v1/payments/{id}` | Retrieve intent (poll) |
| `POST /v1/payments/{id}/cancel` | Cancel unconfirmed intent |
| `GET /v1/payments` | List intents (filters: state, principal, date) |

```json
POST /v1/payments
Idempotency-Key: 8f6c...
{
  "amount_minor": 150000,
  "currency": "KES",
  "destination_wallet": "wal_01HZ...",
  "rail": "honeycoin",
  "metadata": { "order_id": "A-7731" },
  "expires_in_seconds": 900
}
→ 201
{
  "id": "pay_01HZ...",
  "state": "PENDING_PROVIDER",
  "fee": { "amount_minor": 750, "currency": "KES" },
  "next_action": { "type": "none" },
  "created_at": "2026-09-01T10:00:00Z"
}
```

## 3. Transfers, Payouts, Wallets, FX

| Method & path | Purpose |
|---|---|
| `POST /v1/transfers` | Wallet → wallet (internal, instant) |
| `POST /v1/payouts` | Withdraw to external (mpesa / bank / on-chain) |
| `GET /v1/payouts/{id}` | Payout status |
| `POST /v1/payouts/{id}/cancel` | Cancel before provider accepts |
| `GET /v1/wallets` / `GET /v1/wallets/{id}` | List/read wallets & balances |
| `GET /v1/wallets/{id}/statement` | Ledger statement (cursor paginated) |
| `POST /v1/fx/quotes` / `POST /v1/fx/convert` | Quote (TTL'd) → convert (quote id) |

Payout create mirrors payments; destination is
`{ "type": "mpesa", "msisdn": "+2547..." }` or
`{ "type": "bank", ... }` or `{ "type": "on_chain", "network": "base", "address": "0x..." }`.

## 4. Webhooks

- Endpoint registration per API key: `POST /v1/webhook-endpoints` (url, events[], secret).
- Delivery: POST, TLS required, `X-SharkPay-Signature: t=<unix>,v1=<hmac-sha256(t + '.' + body, secret)>`,
  timestamp window ±5 min, replay cache 10 min.
- Retry: exponential 1m → 1h, 8 attempts max; then `webhook_deliveries.state = dead`,
  surfaced in Console.
- Ordering: at-least-once per event; consumers must dedupe on `event.id` and treat state
  as monotonic (see [State Machines](STATE-MACHINES.md)).

### Event catalog (V1)

| Event | Fired when |
|---|---|
| `payment.created` | Intent accepted |
| `payment.pending_provider` | Handed to provider |
| `payment.succeeded` | Funds confirmed & captured to ledger |
| `payment.failed` | Terminal failure (reason included) |
| `payment.expired` | TTL elapsed unconfirmed |
| `payment.reversed` | Compensation entry posted |
| `payout.created` / `payout.processing` | Accepted / handed to provider |
| `payout.sent` / `payout.succeeded` | Rail accepted / confirmed settled |
| `payout.failed` / `payout.returned` | Failed at rail / returned by rail |
| `transfer.succeeded` | Internal transfer committed |
| `fx.quote.locked` / `fx.conversion.executed` | Quote locked / conversion posted |
| `wallet.balance.changed` | Any balance partition change |
| `risk.case.opened` | (Console only) case created |

## 5. Scopes

`payments:read` `payments:write` `payouts:read` `payouts:write` `transfers:write`
`wallets:read` `fx:read` `fx:write` `webhooks:manage` `ops:read` — a key's scopes are
intersected with the agent policy (if the key belongs to an agent principal).

## 6. Quotas & limits (V1 defaults, per key)

| Endpoint class | Burst | Sustained |
|---|---|---|
| Payment/payout create | 50/min | 600/hour |
| Reads | 300/min | 3,600/hour |
| Webhook deliveries (per endpoint) | — | 500/sec spike-safe |

## 7. Versioning policy

- `/v1` additive-only: new optional fields allowed, never remove/rename; state values
  only appended (with ≥ 90-day deprecation notice for retiring a value).
- Breaking changes ⇒ `/v2` with ≥ 12 months overlap.
