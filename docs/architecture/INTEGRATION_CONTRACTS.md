# SharkPay — Integration Contracts

> **Phase:** Audit and planning. This document defines the proposed contracts between SharkPay and integrated applications (Novera, OruCoin, Fuatilia, Rovanta, MjengoOS, and third parties): API boundaries, event names, authentication, idempotency, transaction statuses, ownership, and failure handling.
> It consolidates the **existing** contracts (`contracts/openapi/v1/`, `contracts/events/`) with the **target** contracts the audit identified as missing. Where a contract exists today it is marked **[exists]**; where it must be added it is marked **[gap]**.

---

## 1. Ownership boundaries (non-negotiable)

| Owner | Owns | Does NOT own |
|---|---|---|
| **SharkPay** | Wallets, accounts, double-entry ledger, money movement, provider integrations, reconciliation, fees, limits, refunds, FX, developer platform (keys, webhooks, sandbox), audit trail | Any product's business records |
| **Novera** | Application experience, product-specific financial workflows | Wallet balances, ledger entries |
| **OruCoin** | Digital-asset product logic, asset-specific workflows (within approved custody) | Custody keys, on-chain settlement (SharkPay + custody partner) |
| **Fuatilia** | Invoices, receivables, collection strategies, payment allocation | Payment execution, ledger |
| **Rovanta** | Loan agreements, schedules, repayment rules, lending decisions | Money movement |
| **MjengoOS** | Construction projects, milestone verification, contractor payment requests | Payout execution |

**Rule:** no integrated product may directly modify SharkPay balances or bypass its financial controls. Every interaction is through documented APIs and authenticated events.

---

## 2. Authentication & authorization

### 2.1 Human users
- **OAuth 2.0 / OIDC** via Keycloak. Authorization Code + PKCE S256 for browser/mobile (public clients).
- Roles: `platform-admin`, `tenant-admin`, `operator`, `approver`, `auditor` (read-only).

### 2.2 Service-to-service (internal)
- `client_credentials` grant; one confidential client per service with `service.<domain>.<read|write>` roles (already modeled in the Keycloak realm).
- Internal endpoints under `/internal/...` require a valid service JWT.

### 2.3 Integration credentials (external products) **[exists — api-gateway]**
- **Scoped, rotatable, revocable API keys** (SHA-256 hashed), bound to one organization (`tenant_id`) and a set of scopes.
- Per-key rate limits (rpm) + monthly quotas.
- A compromised Novera key must not grant access to Rovanta wallets — enforced by `tenant_id` binding + RLS.
- Distinct credentials per environment (sandbox vs production).

### 2.4 Webhook verification **[exists]**
- HMAC-SHA256 signature: `X-SharkPay-Signature: t=<unix>,v1=<hmac-sha256>`.
- Timestamp freshness ±5 min; replay cache 10 min (Redis `SET NX`).
- Consumers **must** verify before processing; reject on bad signature/stale/replay.

---

## 3. API contract — public `/v1`

### 3.1 Common conventions **[exists — `common.yaml`]**
- `Money`: `{ amount_minor: int64, currency: string(3), exponent: int }` — integer minor units, never float.
- `Idempotency-Key` header on every mutating request; replay → original response + `X-Idempotent-Replay: true`; same-key/different-payload → `409 idempotency_conflict`.
- `X-Request-Id` echoed on every response.
- Error envelope: `{ "error": { "code", "message", "request_id", "details"? } }`.
- Pagination: `limit` (≤100) + `cursor` → `{ items, next_cursor? }`.
- Currencies (V1): KES, USD, EUR, GBP, USDC, USDT.

### 3.2 Wallet & account operations

| Endpoint | Purpose | Status |
|---|---|---|
| `POST /v1/wallets` | Create an authorized wallet | **[gap]** — internal exists (`/internal/wallets`); public surface via api-gateway [gap H-02] |
| `GET /v1/wallets/{id}` | Retrieve wallet details + balances | **[exists]** `wallets.yaml` |
| `GET /v1/wallets/{id}/balance` | Balance breakdown (available/pending/held) | **[exists]** (folded into `/wallets/{id}`) |
| `GET /v1/wallets/{id}/transactions` | List authorized transactions | **[exists]** as `/wallets/{id}/statement` |
| `GET /v1/wallets` | List wallets (filter principal/currency/status) | **[exists]** |

### 3.3 Money movement

| Endpoint | Purpose | Status |
|---|---|---|
| `POST /v1/transfers` | Internal wallet-to-wallet transfer (instant) | **[exists]** `transfers.yaml` |
| `POST /v1/payment-intents` / `POST /v1/payments` | Initiate a payment workflow | **[exists]** `payments.yaml` |
| `GET /v1/payments/{id}` | Retrieve payment status | **[exists]** |
| `POST /v1/payments/{id}/cancel` | Cancel a pending payment | **[exists]** |
| `POST /v1/refunds` | Request a permitted refund | **[gap]** — reversal exists internally; public refund endpoint not in OpenAPI |
| `POST /v1/payouts` | Request an authorized payout | **[exists]** `payouts.yaml` |
| `GET /v1/payouts` / `GET /v1/payouts/{id}` | List/retrieve payouts | **[exists]**; `GET /payouts` list flagged provisional in SDK |
| `POST /v1/payouts/{id}/cancel` | Cancel a pending payout | **[exists]** |
| `GET /v1/transactions/{id}` | Retrieve transaction details | **[gap]** — ledger has `GET /internal/accounts/{id}/statement` but no `GET /transactions/{id}` [gap C-02] |

### 3.4 FX **[exists — `fx.yaml`]**
| Endpoint | Purpose |
|---|---|
| `POST /v1/fx/quotes` | Quote with fees + expiry |
| `POST /v1/fx/convert` | Execute conversion (4-leg journal) |

### 3.5 Webhook management **[exists — `webhooks.yaml`]**
| Endpoint | Purpose |
|---|---|
| `POST /v1/webhook-endpoints` | Register an event destination |
| `GET /v1/webhook-endpoints` | List |
| `GET /v1/webhook-endpoints/{id}` | Retrieve |
| `DELETE /v1/webhook-endpoints/{id}` | Revoke |
| `PATCH /v1/webhook-endpoints/{id}` | Update |
| `GET /v1/webhook-endpoints/{id}/deliveries` **[gap]** | Delivery history + replay |

### 3.6 Reconciliation references **[gap — internal only]**
- `GET /v1/reconciliation/reports?provider=&from=&to=` (read-only settlement reports) — currently internal only (`/internal/recon/...`). Expose a read-only public view for tenant-scoped reports.

### 3.7 API key management **[gap in OpenAPI]**
- `POST /v1/api-keys`, `GET /v1/api-keys`, `POST /v1/api-keys/{id}/revoke` — implemented in api-gateway but **no merged OpenAPI contract** (SDK is provisional).

### 3.8 Risk cases **[gap]**
- `GET /v1/risk/cases`, `GET /v1/risk/cases/{id}` — no merged contract; SDK provisional.

---

## 4. Transaction status model

Responses distinguish these outcomes (never imply success before confirmation):

| Status | Meaning | Money effect |
|---|---|---|
| `accepted` | Request accepted, processing not started | None yet (hold may be placed) |
| `pending` | In flight at provider / workflow running | Hold placed; no ledger posting yet |
| `succeeded` | Provider confirmed; ledger posted | Ledger entry committed |
| `failed` | Terminal failure | Hold released; no posting (or reversal if posted) |
| `cancelled` | User/system cancelled pre-completion | Hold released |
| `expired` | TTL elapsed without outcome | Hold released |
| `reversed` | Compensating entry posted against a prior success | Reversal ledger entry committed |
| `unknown` | Provider timeout / ambiguous response | **Funds treated as uncertain** — park + alert; reconcile before retry. **Never auto-retry the debit.** |

**Critical rule for integrators:** a response of `pending` or `unknown` is **not** success. Allocate business state (e.g. mark an invoice paid, release a loan obligation) **only** on `succeeded` (or `reversed` for corrections). Subscribe to webhooks for the authoritative terminal transition.

---

## 5. Event catalog (CloudEvents 1.0)

All events are JSON CloudEvents with `id`, `source`, `type`, `time`, `datacontenttype: application/json`, `subject`, and `data`. Published via NATS JetStream (`<domain>.<aggregate>.<event>.v1`). Consumers **must** be idempotent (dedup on `id`) and tolerate out-of-order delivery.

### 5.1 Ledger **[exists — schema] / [gap — publishing]**
| Event type | When | Consumers |
|---|---|---|
| `ledger.posting.committed.v1` | A journal entry commits | wallet (balance projection), reconciliation |

### 5.2 Wallet **[exists — schema] / [gap — publishing]**
| Event type | When | Consumers |
|---|---|---|
| `wallet.balance.changed.v1` | A wallet's projected balance changes | integrators (Novera, etc.) |
| `wallet.hold.placed.v1` | Hold placed | payments, payouts |
| `wallet.hold.released.v1` | Hold released | payments, payouts |
| `wallet.hold.captured.v1` | Hold captured | payments, payouts |
| `wallet.status.changed.v1` | Wallet frozen/unfrozen | integrators |

### 5.3 Payments / Payouts / Transfers **[exists — schema] / [gap — publishing]**
| Event type | When |
|---|---|
| `payments.payment.{created,pending_provider,processing,succeeded,failed,expired,cancelled,reversed}.v1` | Payment state transitions |
| `payouts.payout.{...}.v1` | Payout state transitions |
| `transfers.transfer.{...}.v1` | Transfer state transitions |

### 5.4 FX **[exists — schema] / [gap — publishing]**
| Event type | When |
|---|---|
| `fx.v1` (quote/convert sub-types) | Quote requested / conversion executed |

### 5.5 Risk & Reconciliation **[exists — schema] / [gap — publishing]**
| Event type | When | Consumers |
|---|---|---|
| `risk.v1` (case opened/updated/closed) | Risk evaluation | integrators (compliance) |
| `recon.run.{triggered,succeeded,failed}.v1` | Recon run lifecycle | ops |
| `recon.break.{detected,transitioned}.v1` | Break detected/resolved | ops |
| `recon.compensation.{proposed,executed}.v1` | Compensation lifecycle | ops, audit |

> **Doc drift to fix:** `contracts/events/events.md` and ~20 Java comments still say "Kafka." Update to NATS JetStream (ADR 001 §3).

---

## 6. Idempotency contract

- **Every mutating endpoint** accepts `Idempotency-Key` (UUID v4 recommended).
- Server stores `(tenant_id, scope, key) → (request_fingerprint, entity_id)`.
- Same key + same payload → `200` + original response + `X-Idempotent-Replay: true`.
- Same key + different payload → `409 idempotency_conflict`.
- Retries (network, 5xx, 401-refresh) **must reuse the same key** — this is what makes retries safe.
- Integrators should generate one key per logical operation and reuse it across all retries.

---

## 7. Failure handling & retries

### 7.1 Client retry rules
- **5xx + transport errors:** retry with exponential backoff + jitter (e.g. `300ms · 2^(n-1)`), max 2–3 retries. Safe because every mutation is idempotency-keyed.
- **4xx + 429:** never retry (client error / rate limit).
- **401:** refresh token, retry **once** with the same `Idempotency-Key`.
- **Timeouts:** treat as `unknown` — do not assume failure. Query `GET /payments/{id}` to reconcile.

### 7.2 Provider timeout / ambiguous outcome
- A provider timeout is **uncertain**. SharkPay parks the payment as `pending`/`unknown` and alerts.
- Reconciliation queries the provider before deciding a retry is safe.
- **Integrators must not** treat a timeout as success or failure — wait for the webhook.

### 7.3 Webhook delivery
- Exponential retry 1m → 1h, ≤8 attempts, then dead-letter + auto-pause + manual replay.
- Consumers dedup on event `id`; out-of-order delivery converges (wallet's leg-level dedup is the reference).
- A webhook is **not** the only signal — `GET` endpoints remain queryable for reconciliation.

### 7.4 Reconciliation
- Every provider + settlement account has a defined reconciliation process.
- Mismatches (`missing_on_provider`, `missing_internal`, `amount_mismatch`, `status_mismatch`, `fee_mismatch`, `currency_mismatch`) enter an exception queue, assigned + aged, visible until resolved.
- Compensations require 4-eyes (proposer ≠ approver).

---

## 8. Product-specific integration contracts

### 8.1 Novera (wallet experiences)
- **Novera → SharkPay:** `POST /v1/wallets`, `GET /v1/wallets/{id}/balance`, `POST /v1/transfers`, `POST /v1/payment-intents`.
- **SharkPay → Novera:** `wallet.balance.changed.v1`, `payments.payment.succeeded.v1`.
- **Novera owns:** UX, product financial workflows. **SharkPay owns:** balances, ledger.

### 8.2 OruCoin (approved digital assets)
- **OruCoin → SharkPay:** `POST /v1/payouts` (asset rail), `POST /v1/fx/convert` (on/off-ramp), `GET /v1/transactions/{id}`.
- **SharkPay → OruCoin:** `payouts.payout.{succeeded,failed}.v1`, `fx.v1` conversion events.
- **OruCoin owns:** asset product logic. **SharkPay + custody partner own:** keys, signing, on-chain settlement. **Gated by:** approved custody (AST-007).

### 8.3 Fuatilia (collections & invoice matching)
- **Fuatilia → SharkPay:** `POST /v1/payment-intents` (with payment reference = invoice id), `GET /v1/transactions/{id}`.
- **SharkPay → Fuatilia:** `payments.payment.succeeded.v1` (Fuatilia matches to invoice idempotently).
- **Fuatilia owns:** invoices, receivables, allocation. **SharkPay owns:** payment execution.

### 8.4 Rovanta (loan repayments)
- **Rovanta → SharkPay:** `POST /v1/payment-intents` (idempotency key = repayment id; reference = loan id).
- **SharkPay → Rovanta:** `payments.payment.succeeded.v1` → Rovanta allocates to loan.
- **Rovanta owns:** loan agreements, schedules, lending decisions. **SharkPay owns:** money movement.

### 8.5 MjengoOS (contractor payouts)
- **MjengoOS → SharkPay:** `POST /v1/payouts` (reference = milestone id; metadata = project id).
- **SharkPay → MjengoOS:** `payouts.payout.{succeeded,failed}.v1`.
- **MjengoOS owns:** projects, milestone verification. **SharkPay owns:** payout execution.

### 8.6 Third-party / future
- Same contract surface. Provisioned via `POST /v1/webhook-endpoints` + scoped API key + tenant.

---

## 9. Versioning & compatibility

- APIs are **versioned** (`/v1`). Breaking changes require `/v2`; `v1` remains supported through a deprecation window.
- OpenAPI contracts are **append-only** (ADR 003 §2) — adding fields/endpoints is allowed; removing/renaming is a breaking change.
- Events are versioned (`*.v1`); schema evolution follows CloudEvents + JSON Schema compatibility rules (additive only).
- Integrators must ignore unknown fields (forward compatibility).

---

## 10. Contract test obligations

Each product integration must ship **contract tests** (Epic M-02) verifying:
1. Authentication: valid key → 200; invalid/revoked → 401; cross-tenant → 403.
2. Idempotency: replay → same response + `X-Idempotent-Replay`; conflict → 409.
3. Status semantics: integrator allocates business state only on `succeeded`/`reversed`.
4. Webhook: signature verifies; replay rejected; delivery idempotent.
5. Ownership: integrator never writes balances; every write goes through SharkPay APIs.

These tests are the gate for product integration approval.

---

## 11. What an integrator needs to onboard (checklist)

- [ ] Organization + tenant provisioned in SharkPay (PRJ-009).
- [ ] Scoped API key issued (sandbox first, then production).
- [ ] Webhook endpoint registered + signature verification implemented.
- [ ] Idempotency-key generation per logical operation.
- [ ] Status handling: act only on `succeeded`/`reversed`; treat `unknown` as uncertain.
- [ ] Retry policy: 5xx/transport retry with same idempotency key; 4xx/429 no retry; 401 refresh + retry once.
- [ ] Contract tests passing against sandbox.

When all boxes are checked, the integrator is production-ready against the SharkPay shared platform.

---

*This document is the contract baseline for the SharkPay shared platform. It is derived from existing contracts (`contracts/openapi/v1/`, `contracts/events/`) and the audit's gap findings. Implementation issues for each **[gap]** row are in the GitHub backlog.*
