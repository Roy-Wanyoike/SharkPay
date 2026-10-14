# SharkPay — Target Architecture

> **Phase:** Audit and planning. This document describes the *target* architecture and the decisions required to reach it, derived from the evidence-based audit (`docs/audit/AUDIT.md`). It is a decision document, not a claim that the current repository implements all of it.
> **Status of key ADRs:** ADR 001 (stack lock) — **Accepted**. ADR 003 (safe parallelism + verification) — **Accepted**. ADR 002 (polyglot backend strategy) — **Proposed, pending owner confirmation** (the single most important open decision; see §10).

---

## 1. System context

SharkPay is the **authoritative wallet and financial execution platform** for a family of products. It owns the ledger, money movement, provider integrations, reconciliation, and the developer platform. Integrated products own their own business domains and interact with SharkPay only through versioned APIs and signed webhooks.

```
                        ┌─────────────────────────────────────────────┐
   Client products      │ Novera · OruCoin · Fuatilia · Rovanta ·     │
   (own their domain)   │ MjengoOS · third-party integrations         │
                        └───────────────┬─────────────────────────────┘
                                        │  versioned REST /v1 + signed webhooks
                                        │  OAuth2/OIDC + scoped API keys
                                        ▼
                        ┌─────────────────────────────────────────────┐
   SharkPay platform    │ api-gateway (public edge: keys, webhooks,    │
   (owns money)         │   quotas, sandbox)                           │
                        │  ┌────────────────────────────────────────┐ │
                        │  │ identity · wallet · payments · payouts │ │
                        │  │ fx · risk · reconciliation             │ │
                        │  │ ledger (Go, source of truth) ·         │ │
                        │  │ providers (Go, rails gateway)          │ │
                        │  └────────────────────────────────────────┘ │
                        │  Temporal (sagas) · NATS JetStream (events) │
                        │  PostgreSQL (authority) · Redis (cache only)│
                        └───────────────┬─────────────────────────────┘
                                        │  provider adapters (HoneyCoin, …)
                                        ▼
                        ┌─────────────────────────────────────────────┐
   External rails       │ M-Pesa · banks · card processors ·          │
                        │ approved digital-asset providers            │
                        └─────────────────────────────────────────────┘
```

**Boundary rule (non-negotiable):** the ledger is the only authoritative source of financial truth. Caches, events, and provider responses may never independently override it. No integrated product may directly modify SharkPay balances or bypass its financial controls.

---

## 2. Component diagram and module boundaries

### 2.1 Services and ownership

| Service | Runtime | Owns | Does NOT own |
|---|---|---|---|
| **ledger** | Go | Double-entry journal, postings, balances, reversals, account chart | Wallet UX, payment state, provider calls |
| **providers** | Go | Provider abstraction, HoneyCoin adapter, callback ingress, router, circuit breaker, audit trail | Money state, ledger posting |
| **identity** | Java/Spring | SharkID, principals, KYC tiers, device binding | Wallets, payments |
| **wallet** | Java/Spring | Wallet lifecycle, holds (place/release/capture), balance projection (consumes ledger events) | Ledger entries, provider calls |
| **payments** | Java/Spring + Temporal | Payment intents, 9-state machine, orchestration saga, fees | Ledger, holds, provider execution (calls ports) |
| **payouts** | Java/Spring + Temporal | Transfers (internal instant), external-rail payouts, scheduled batches | Ledger, holds, provider execution (calls ports) |
| **fx** | Java/Spring | FX quotes (TTL), conversion, 4-leg journal entries, position reconciliation | Rate source (port), ledger (port) |
| **risk** | Java/Spring | Rules engine, velocity counters, compliance cases | Money movement |
| **reconciliation** | Java/Spring | Recon runs, breaks, 4-eyes compensations, settlement reports | Ledger (port), provider statements (port) |
| **api-gateway** | Java/Spring | API keys (rotatable, scoped, quota'd), HMAC webhook dispatcher, sandbox, public edge | Upstream business logic (routes to services) |

### 2.2 Why polyglot (ADR 002 — pending sign-off)

The audit confirmed the repository is already polyglot by design: Go for the core money path (ledger, providers) where the concurrency + pgx + distroless footprint pays off; Java/Spring for the surrounding platform services where the team has built mature hexagonal domains with Temporal/JPA/Security. **The recommendation is to accept the polyglot split (ADR 002 → Accepted)** rather than force a rewrite either way:

- Rewriting 8 mature Java services (647 main + 308 test files) to Go would discard tested domain logic and block delivery for months.
- Rewriting the Go ledger to Java would discard the strongest, most correctness-critical component (three-layer invariant defense, append-only SQL roles, deadlock-free locking).

Every capability must exist **exactly once**. The seam between Go and Java is the ledger's internal HTTP API (already built) consumed by a Java `LedgerPort` adapter (currently a fail-fast stub — the highest-priority integration task).

### 2.3 Module dependency rules (enforced by ADR 003 §2)

- `domain/` depends on nothing.
- `ports/` depends on `domain/` only.
- `service/` (use cases) depends on `domain/` + `ports/`.
- `storage/` + `api/` + `events/` depend on `ports/` (adapters).
- `config/` wires adapters.
- Cross-service calls go through a `port/` interface, never a direct import. The 17 `IntegrationPending*` stubs are the unwired ports.

---

## 3. Database and tenant-isolation strategy

### 3.1 Storage authority

- **PostgreSQL 18** is the only authoritative store for ledger entries, transactions, account ownership, holds, idempotency records, and audit data.
- **Redis** is cache/rate-limit/replay-nonce only — never a source of truth for balances or the only record of a payment.
- One shared cluster, **one schema per service** (`ledger`, `identity`, `wallet`, …) with a dedicated LOGIN role and pinned `search_path`. This is already implemented in `infrastructure/dev/postgres/init/01-schemas.sql`.

### 3.2 Migrations

- **Go ledger:** hand-written SQL migrations under `services/ledger/migrations/` (current: `001_ledger_init.sql`). Apply via a versioned migration runner in the release pipeline (gap: none automated today).
- **Java services:** Flyway, `classpath:db/migration`, `V<seq>__<name>.sql`. **Gap: zero migrations exist today** — the highest-priority database work (see GAP_ANALYSIS).
- `jpa.hibernate.ddl-auto=validate` in every Java service — Hibernate never creates schema; Flyway owns it.

### 3.3 Tenant isolation (target)

Today the data model is **single-tenant** (no `tenant_id`/`organization_id` columns, no RLS). The target requires multi-tenancy because multiple products/companies share the platform:

1. **Every financial table gains `tenant_id`** (organization) as a NOT NULL FK, indexed, and included in every unique constraint that spans tenant-owned rows (e.g. idempotency keys become `UNIQUE(tenant_id, scope, key)`).
2. **Application-layer authorization** is the primary enforcement: every query filters by the caller's tenant (resolved from the authenticated principal / API key).
3. **PostgreSQL Row-Level Security (RLS)** as defense-in-depth: a session sets `SET LOCAL app.tenant_id = ?` and RLS policies filter all tenant-owned tables. RLS is **not** a replacement for app authorization — it catches leaks.
4. **Background jobs and event consumers** carry tenant context in the message payload and re-assert ownership before any write.
5. **Nested-resource checks**: a wallet belongs to a tenant; a hold belongs to a wallet; every layer re-verifies ownership.

### 3.4 Concurrency model (preserve from ledger)

The ledger's concurrency pattern is the canonical reference for any service writing financial state:
- `SELECT … FOR UPDATE` one-row-at-a-time in a **deterministic ascending order** (UUID sort) to dodge PostgreSQL's multi-row `ORDER BY FOR UPDATE` deadlock caveat.
- Re-assert invariants **under the locks**, not before.
- Deferred CONSTRAINT triggers as a COMMIT-time backstop.
- This pattern must be replicated in wallet (holds), payments (state transitions), and any new financial writer.

---

## 4. Ledger and transaction lifecycle

### 4.1 Ledger model (preserve as-is)

- **Double-entry, per-currency balance:** Σdebit = Σcredit per currency per journal entry; enforced in `domain`, under locks in `storage`, and at COMMIT by trigger `trg_entry_balanced`.
- **Append-only journal:** `journal_entries` + `postings` grant only `SELECT, INSERT` to the app role; `UPDATE`/`DELETE` revoked. Corrections are **compensating reversal entries** (`reverses_entry_id`), never edits.
- **Idempotency:** `UNIQUE(source, transaction_key)`; a replay returns the original entry; a same-key/different-payload attempt returns a conflict.
- **Money:** `int64` minor units, `CHECK (debit=0 OR credit=0)`, `CHECK (debit+credit>0)`, 1e15 bound.
- **Accounts:** typed (`wallet`, `fee`, `fx`, `suspense`, `treasury`, `reserve`, `settlement`), currency-pinned, owner-linked.

### 4.2 Transaction lifecycle (payment state machine)

The payments service already implements the target state machine; preserve it:

```
CREATED → PENDING_PROVIDER → PROCESSING → SUCCEEDED
   │           │                  │
   │           ↓                  ↓
   ↓      FAILED             CANCELLED
BLOCKED
   │
   └→ (terminal) FAILED / EXPIRED / REVERSED
```

- Money side-effects (hold/release/capture/reversal) happen **before** the state transition; entry ids thread into the append-only transition log.
- **Ambiguity contract:** a provider timeout/unknown is treated as an **uncertain outcome** — park + alert, never auto-retry the debit. Reconcile/query the provider before deciding a retry is safe.
- Reversal is a compensating ledger entry, idempotent on `ops:rev:<id>`, blocked if already reversed.

### 4.3 Holds lifecycle (wallet)

`place → capture(amount) → released/captured`; partial capture invariant `captured + released == amount` enforced. Available balance = projected total − Σ(ACTIVE holds). Holds are the wallet-level reservation; the ledger records the actual money movement on capture.

---

## 5. API and webhook architecture

### 5.1 Public API (`/v1`)

- **Versioned REST**, OpenAPI 3.1, append-only (ADR 003 §2). Current coverage: `payments`, `wallets`, `fx`, `payouts`, `transfers`, `webhooks` + `common`.
- Every mutating endpoint accepts an `Idempotency-Key` header; replay returns the original response with `X-Idempotent-Replay: true`; same-key/different-payload → 409 `idempotency_conflict`.
- Canonical error envelope: `{ "error": { "code", "message", "request_id", "details"? } }` with stable domain codes (`validation_error`, `not_found`, `state_conflict`, `idempotency_conflict`, `insufficient_funds`, `currency_mismatch`, `money_overflow`, `unsupported_currency`, `risk_blocked`, `reversal_exceeds_captured`).
- Transaction statuses distinguish `accepted`, `pending`, `succeeded`, `failed`, and **`unknown`** (investigation required) — never imply success before confirmation.

### 5.2 Internal API

- Service-to-service over **REST** (the "internal gRPC with mTLS" in `ARCHITECTURE.md` is **unimplemented and not recommended for V1** — REST is sufficient, simpler to operate, and the contracts already exist in OpenAPI). gRPC may be revisited if latency profiling demands it.
- Internal endpoints live under `/internal/...` and require a service-account JWT (`client_credentials` grant) with `service.<domain>.<read|write>` roles (already modeled in the Keycloak realm).
- The ledger's internal API (`POST /internal/transactions`, `POST /internal/transactions/{id}/reverse`, `GET /internal/accounts/{id}/statement`, `POST /internal/accounts`) is the canonical seam between Go and Java.

### 5.3 Webhooks

- **HMAC-SHA256** signature: `X-SharkPay-Signature: t=<unix>,v1=<hmac>`. ±5min timestamp window, 10min replay cache (Redis `SET NX`).
- Retry: exponential 1m → 1h, ≤8 attempts, then dead-letter + auto-pause + manual replay.
- Delivery management endpoint exposes history and replay.
- Webhook event catalog is the CloudEvents schema registry (`contracts/events/`).

---

## 6. Provider abstraction

### 6.1 Interface (preserve from Go providers service)

```
Provider {
  Name() string
  Capabilities() Capabilities            // currencies, rails, reversals, on-chain
  Quote(ctx, QuoteRequest) (Quote, error)
  Initiate(ctx, InitiateRequest) (ProviderRef, error)
  Poll(ctx, ProviderRef) (TransferStatus, error)
  HandleCallback(ctx, Callback) (TransferStatus, error)
  Cancel(ctx, ProviderRef) error
  Reverse(ctx, ProviderRef) (ProviderRef, error)
  ReconcileReport(ctx, Window) ([]Line, error)
}
```

- **TransferStatus** includes `StatusUnknown` as a first-class state (park + alert, never auto-retry).
- Typed errors: `ErrProviderUnavailable` (fail-over signal), `ErrUnsupported`, `ErrUnsupportedCurrency`, `ErrNotFound`.

### 6.2 Routing

Hard filters (fail-closed): currency, rail, breaker-OPEN excluded, on-chain requires `OnChain` capability, KYC-tier gate. Scoring: `0.5·costNorm + 0.3·latencyNorm + 0.2·healthPenalty`, deterministic name tiebreak.

**Gap to close:** breaker state must propagate to the router hard-filter on every state transition (today registry health is set manually once at boot).

### 6.3 Adapter responsibilities (preserve)

- **Audit-before-return:** every call funnels through `call()` which defers `finish()`; a failing audit store fails the call closed.
- **Integer fidelity:** `json.Decoder.UseNumber()` in the audit trail to prevent float64 round-tripping above 2^53.
- **Idempotency:** `X-Idempotency-Key` on `Initiate`; derived keys for cancel/reverse.
- **Callback ingress:** signed, freshness-checked, replay-protected, fail-closed on cache failure.

### 6.4 Provider roadmap

- **HoneyCoin** — launch provider (real adapter exists, points at WireMock in dev). Confirm commercial approval + production access before live.
- **Flutterwave** — candidate African provider for M-Pesa/bank/card. Assess after HoneyCoin end-to-end.
- **Wise Platform** — candidate for international transfers. Assess corridors + settlement terms.
- Each provider's capabilities (country, currency, collection/payout methods) must be **verified, not assumed**.

---

## 7. Event delivery and workflow orchestration

### 7.1 Transactional outbox (target — currently absent)

Every financial database change commits **together** with an outbox row in the same transaction. A separate publisher reads the outbox and publishes to NATS JetStream. Consumers are idempotent (dedup on event id).

```
[begin tx]
  INSERT INTO journal_entries ... ;   -- the money change
  INSERT INTO postings ... ;
  INSERT INTO outbox (event_id, topic, payload) ... ;   -- same tx
[commit]
-- publisher (separate process) reads outbox, publishes to NATS, marks published
```

**Why:** a database write followed by an unprotected message publish can lose events (publish fails) or double-fire (publish succeeds, commit rolls back). The outbox makes the event-durable with the money change. **This is the largest cross-cutting gap** — every `LoggingEventPublisher` must become a real outbox+NATS publisher.

### 7.2 NATS JetStream

- Persisted streams, acknowledgments, redelivery. Consumers handle duplicate deliveries safely (wallet's `ApplyLedgerEventUseCase` is the reference: event-id dedup + `(wallet, posting)` leg-level dedup, out-of-order converges).
- Topic naming: `<domain>.<aggregate>.<event>.v1` (e.g. `ledger.posting.committed.v1`, `payments.payment.succeeded.v1`, `wallet.balance.changed.v1`).
- **Doc drift to fix:** `contracts/events/events.md` and ~20 Java comments still say "Kafka." Update to NATS.

### 7.3 Temporal

- **Payments + payouts** use Temporal for long-running sagas (provider polling, delayed callbacks, retries, compensation). The `PaymentWorkflowImpl` is the reference: deterministic, no money math in workflow code, compensation always via `failPayment`/`expirePayment` (release hold exactly once).
- **Enable by default in dev/staging** (`temporal.enabled=true`) once the Temporal healthcheck is added to compose and the 4 payments stubs are wired.
- Activities are the only boundary that touches external services (provider gateway, ledger, wallet). Never call ports from workflow code.

---

## 8. Authentication and authorization

### 8.1 Human authentication

- **Keycloak** (OAuth 2.0 / OIDC). Authorization Code + PKCE S256 for web + mobile (public clients `sharkpay-web`, `sharkpay-mobile`).
- Session: web seals `{user, accessToken, refreshToken, idToken, issuedAt, expiresAt}` into an HttpOnly/Secure/SameSite=Lax cookie via AES-256-GCM (Web Crypto). TTL 8h.
- **Gap:** wire `refreshAccessToken` into a server-side 401-recovery path (currently dead code); mobile already does 401→refresh→retry.

### 8.2 Service authentication

- `client_credentials` grant; one confidential client per service (`sharkpay-{service}`) with a service account mapped to `service.<domain>.<read|write>` roles (already in the Keycloak realm).
- Each service validates the JWT in `SecurityConfig` and **must** enforce roles (gap: no role checks today).

### 8.3 API credentials (for integrations)

- Scoped, rotatable, revocable API keys (SHA-256 hashed) managed by `api-gateway`. Per-key rpm + monthly quotas. HMAC-signed webhooks.
- A compromised Fuatilia credential must not grant access to Rovanta wallets — enforced by `tenant_id` scoping + per-key role/tenant binding.

### 8.4 RBAC target (gap to close)

Roles to enforce in `SecurityConfig` + method-level `@PreAuthorize`:
- `platform-admin`, `tenant-admin`, `operator`, `approver`, `auditor` (read-only).
- Maker-checker for designated high-risk operations (reconciliation compensation already enforces 4-eyes at the use-case layer; promote to a role check: `approver` ≠ `requester`).

---

## 9. Observability, deployment, and recovery

### 9.1 Observability (overlay exists; services must emit)

- **OpenTelemetry** SDK in every service; OTLP exporter to the collector. Correlation IDs (`X-Request-Id`) propagated across services.
- **Prometheus** metrics: payment latency, failed transactions, provider availability, reconciliation backlog, workflow failures, ledger posting rate. **Gap:** services don't emit the metrics the K8s AnalysisTemplates query → canary analysis is Inconclusive.
- **Grafana** dashboards (6 provisioned), **Loki** logs, **Tempo** traces, **Alertmanager** + 23 alert rules + 9 runbooks.

### 9.2 Deployment

- **Docker** images per service; **K8s** (base + dev/staging/prod overlays) via **Argo CD** app-of-apps. ledger/providers/payments use Argo **Rollouts** (canary, SLO-gated promotion); others use Deployments.
- **Gap:** no CI release pipeline builds/pushes images or promotes tags. The `PLACEHOLDER` image tags in prod overlays must be filled by a release workflow (build → push to GHCR → commit `sha-<git>` tag → Argo sync).
- prod: 3 replicas (ledger/providers 4), HPA 2..10, multi-AZ topology spread, PDB minAvailable 2.

### 9.3 Recovery

- **PostgreSQL** backups (managed platform PITR) + **regularly tested restore** (gap: no automation).
- **Temporal** workflow state is durable — a worker restart resumes in-flight sagas.
- **Outbox** makes event delivery crash-safe.
- **Disaster recovery:** define RPO/RTO; document runbook (gap).

---

## 10. Decisions, trade-offs, and unresolved questions

### Decisions to ratify

| # | Decision | Status | Rationale |
|---|---|---|---|
| D1 | **Accept polyglot Go + Java** (ADR 002 → Accepted) | Pending owner sign-off | Preserves the strongest components on each side; avoids a multi-month rewrite. The Go/Java seam is the ledger internal HTTP API. |
| D2 | **REST for internal S2S**, not gRPC | Proposed | Contracts already exist in OpenAPI; REST is simpler to operate. Drop the "internal gRPC with mTLS" claim from `ARCHITECTURE.md`. Revisit only if latency profiling demands it. |
| D3 | **NATS JetStream** as the only event bus | Accepted (ADR 001) | Fix doc drift ("Kafka" → "NATS") in `events.md` + ~20 Java comments. |
| D4 | **Transactional outbox** for all event publishing | Proposed | Mandated by financial correctness (invariant #10). |
| D5 | **Multi-tenancy via `tenant_id` + RLS** | Proposed | Required for the shared-platform mission. |
| D6 | **Flyway for all Java services** | Accepted (ADR 001) | Migrations must be written — the largest single workstream. |

### Trade-offs

- **Polyglot** adds operational complexity (two build toolchains, two observability patterns) but preserves correctness and velocity. Mitigated by shared contracts (OpenAPI/CloudEvents) and a single CI ladder (G1–G5).
- **Modular monolith vs microservices:** the repo is already decomposed into 10 services. The recommendation is to *keep* the decomposition (the boundaries are clean) but operate them as a single deployable unit per environment initially, extracting only when scale demands. The K8s manifests already support this.
- **Redis as cache only:** deliberately not authoritative. The ledger is the truth; Redis accelerates reads and coordinates short-lived concerns (replay nonces, rate limits).

### Unresolved questions (need business/security/regulatory input)

1. **Custody model for digital assets** (OruCoin) — software holds ≠ legal escrow. Which custody partner? (AST-007).
2. **Regulatory licensing per market** — multicurrency support ≠ permission to hold customer funds / provide payment services / conduct FX in every jurisdiction. Activate each market only after approval.
3. **Provider commercial approval** — HoneyCoin production access; Flutterwave/Wise assessment terms.
4. **Data retention policy** — financial records retention per jurisdiction (REP-008).
5. **Tenant onboarding workflow** — who provisions organizations + credentials (PRJ-009)?

---

## 11. Target-state checklist (what "done" looks like for V1)

- [ ] ADR 002 Accepted; `ARCHITECTURE.md` + `events.md` + Makefile + `verify.yml` comment de-drifted.
- [ ] Flyway `V1__<service>_init.sql` for all 8 Java services, validated against Postgres via Testcontainers.
- [ ] All 17 `IntegrationPending*` adapters implemented as REST clients (or outbox/NATS for event sources).
- [ ] Transactional outbox + NATS `EventPublisher` in every service; `LoggingEventPublisher` retired to tests.
- [ ] Wallet's `LedgerEventConsumer` reads from NATS (not the dev HTTP endpoint).
- [ ] Temporal enabled in dev/staging; healthcheck added; payments saga runs end-to-end against fakes.
- [ ] One payment completes end-to-end: payments → risk → wallet(hold) → providers(initiate) → ledger(post) → callback → wallet(capture) → ledger(post) → webhook to a test consumer.
- [ ] `tenant_id` + RLS on all tenant-owned tables; RBAC enforced in every `SecurityConfig`.
- [ ] Providers: Postgres `adapter_calls` audit store; breaker→router propagation; `/readyz` + auth + request-ID envelope.
- [ ] CI: release pipeline (build→push→tag→sync); security scanning (CodeQL/Trivy/Dependabot); e2e workflow; load test.
- [ ] Web: create flows wired; 401-refresh; developer portal; `AUTH_SECRET` ≥32.
- [ ] Observability: services emit OTel + Prometheus metrics; canary AnalysisTemplates go green.

When every box is checked, SharkPay can serve as the shared wallet platform for the first product integration.
