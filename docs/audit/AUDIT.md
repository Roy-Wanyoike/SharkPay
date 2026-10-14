# SharkPay — Principal Engineering Audit

> **Phase:** Audit and planning only. No production code, schema, or deployment was changed during this audit.
> **Audited at:** `main` @ `e09dd98` (after the two Vercel build fixes — `82c498b` scope `vercel.json` to web; `e09dd98` force nodejs middleware runtime).
> **Auditors:** Principal engineer coordinating four parallel read-only audits (Go services; Java services + mobile; web console; DB/infra/contracts/packages/CI/docs).
> **Method:** Every conclusion is backed by code, schema, config, or a stated limitation. Speculation is explicitly flagged.

---

## 0. Headline finding (read this first)

The README and `docs/ARCHITECTURE.md` describe SharkPay as *"Foundation / specification stage … scaffolding only"* with *"Microservices in Go"* and *"Kafka domain events"* and *"internal gRPC with mTLS."* **None of that is accurate.** The repository is substantially built, and the actual state is:

| Claim (README / ARCHITECTURE.md) | Reality (code) |
|---|---|
| "Implementation work has not started — scaffolding only" | 10 domain services + 2 client apps implemented with tests. ~1,200 source files. |
| "Microservices in Go" | **Polyglot:** 2 services in Go (`ledger`, `providers`); **8 services in Java 25 / Spring Boot 4.1.1** (`identity`, `wallet`, `payments`, `payouts`, `fx`, `risk`, `api-gateway`, `reconciliation`). |
| "Kafka domain events" | **NATS JetStream** is the locked choice (ADR 001 §3) and is provisioned in dev — but **no service publishes events to it**. Every `EventPublisher` is a `LoggingEventPublisher` that writes CloudEvents to SLF4J only. |
| "Internal gRPC with mTLS" | **No `.proto` files exist anywhere.** Cross-service calls are REST (when wired) or fail-fast stubs. |
| PostgreSQL 16+ | PostgreSQL **18** (ADR 001) — dev stack ships `postgres:18-alpine`. |

The financial core (the Go `ledger`) is genuinely strong — true double-entry, append-only journal enforced at the SQL role level, idempotency, deadlock-free concurrency, integer money. The Java services are architecturally mature at the unit/use-case/Mockmvc level but **operationally blocked from production**: no Flyway migrations exist, all 17 cross-service ports are fail-fast stubs, event publishing is logging-only, and Temporal is disabled by default.

**This is a platform with a correct heart and disconnected limbs.** The audit below quantifies that precisely.

---

## 1. Repository baseline

- **Repository:** `github.com/Roy-Wanyoike/SharkPay`, branch `main`, HEAD `e09dd98`.
- **Working-tree status:** clean (the two Vercel fixes are committed; `worklog.md` is git-ignored).
- **Monorepo layout** (matches README):
  - `apps/{web,mobile}` — Next.js 16 console; Expo 57 / React Native 0.86 wallet app.
  - `services/{ledger,providers,identity,wallet,payments,payouts,fx,risk,api-gateway,reconciliation}` — 10 services.
  - `packages/{go/money,java/sharkpay-money,ts/sharkpay-client}` — 3 shared libraries.
  - `contracts/{openapi/v1,events}` — OpenAPI 3.1 + CloudEvents JSON schemas.
  - `infrastructure/{dev,k8s,observability}` — dev stack, K8s/GitOps, observability overlay.
  - `docs/` — 14 docs + 3 ADRs (~7,500 lines).
  - `tests/` — provider conformance suite (3k Go lines, in-process fake).
- **Build/verify commands:** `scripts/verify-all.sh` (local mirror of CI G1–G5 ladder); CI is `.github/workflows/verify.yml` (6 jobs: `contracts`, `go`, `java`, `web`, `compose-config`, `gate`).
- **Not run during audit:** builds/migrations/destructive ops were deliberately avoided per the phase rules. Findings are from static reading + the in-repo CI definition, not from execution.

---

## 2. Existing architecture

### 2.1 Service inventory and runtime

| Service | Runtime | Port | DB schema | Domain | Real impl? |
|---|---|---|---|---|---|
| ledger | Go 1.24 | 8090 | `ledger` | Double-entry source of truth | **Yes** (664-line postgres store, 446-line service test) |
| providers | Go 1.24 | 8091 | (none — audit store in-memory) | Provider gateway + HoneyCoin adapter | **Yes** (real HTTP adapter, HMAC signing, callback verify, Redis replay cache) |
| identity | Java 25 / Spring Boot 4.1.1 | 8082 | `identity` | SharkID, KYC, principals, devices | **Yes** (322 main + 151 test files across 4 in-scope Java services) |
| wallet | Java 25 / Spring Boot 4.1.1 | 8083 | `wallet` | Wallets, holds, balance projection | **Yes** |
| payments | Java 25 / Spring Boot 4.1.1 | 8086 | `payments` | Payment intents + Temporal saga | **Yes** (Temporal workflow real but disabled by default) |
| payouts | Java 25 / Spring Boot 4.1.1 | 8087 | `payouts` | Transfers + external-rail payouts | **Yes** |
| fx | Java 25 / Spring Boot 4.1.1 | 8084 | `fx` | FX quotes + conversion | **Yes** |
| risk | Java 25 / Spring Boot 4.1.1 | 8085 | `risk` | Rules engine, velocity, compliance cases | **Yes** |
| api-gateway | Java 25 / Spring Boot 4.1.1 | 8088 | `api_gateway` | API keys, webhooks, sandbox | **Yes** |
| reconciliation | Java 25 / Spring Boot 4.1.1 | 8089 | `reconciliation` | Recon runs, breaks, 4-eyes compensations | **Yes** |

### 2.2 Hexagonal architecture (ports & adapters)

All four Java services audited deeply (identity, wallet, payments, reconciliation) follow a clean hexagonal layout: `domain/` (pure), `ports/` (interfaces), `service/` (use cases), `storage/` (JPA adapters), `api/` (controllers), `config/` (wiring), `events/` (CloudEvent envelopes), `fakes/` + `testsupport/` (in-tree test doubles).

**Critical pattern:** each service declares its cross-service dependencies as `ports/` interfaces, and the production adapters are named `IntegrationPending*` and throw `IllegalStateException` on every call. This is a deliberate "fail-fast until wired" stance (ADR 003 §3 — consumer-driven ports with in-tree fakes). It means the domain logic is fully testable today, but the services cannot collaborate end-to-end.

### 2.3 The 17 IntegrationPending* stubs (the integration wall)

| Service | Stubbed port | What it blocks |
|---|---|---|
| payments | `IntegrationPendingRiskPort` | Risk evaluation |
| payments | `IntegrationPendingWalletHoldPort` | Hold place/release/capture |
| payments | `IntegrationPendingLedgerPort` | Ledger post + reverse (`postEntry`/`reverseEntry` throw) |
| payments | `IntegrationPendingProviderGateway` | Provider initiate/poll |
| wallet | `IntegrationPendingPrincipalLookup` | Principal verification |
| wallet | `IntegrationPendingLedgerAccounts` | Ledger account provisioning |
| reconciliation | `IntegrationPendingProviderStatement` | Provider statement pull |
| reconciliation | `IntegrationPendingLedgerStatement` | Internal ledger statement pull |
| reconciliation | `IntegrationPendingLedgerPort` | Compensation posting |
| fx | `IntegrationPendingRateProvider` | Rate source |
| fx | `IntegrationPendingLedgerPort` | FX journal posting |
| payouts | `IntegrationPendingWalletHoldPort` | Hold ops |
| payouts | `IntegrationPendingPrincipalLookup` | Principal verification |
| payouts | `IntegrationPendingLedgerPort` | Ledger posting |
| payouts | `IntegrationPendingProviderGateway` | Provider payout |
| api-gateway | `IntegrationPendingUpstream` | Routing to backend services |
| api-gateway | `IntegrationPendingEventSource` | Webhook event ingestion |

Until these 17 adapters are implemented as real REST (or gRPC) clients, SharkPay cannot move a single payment end-to-end, regardless of how correct each domain is in isolation.

### 2.4 Client apps

- **`apps/web`** (Next.js 16, React 19, TypeScript, Tailwind 4): a real operations console with 10 route pages (dashboard, payments, payouts, wallets, fx, risk, api-keys, webhooks, settings, login). Auth via AES-256-GCM-sealed session cookie + Keycloak PKCE S256 (or mock mode). **Currently a read-only dashboard**: every API call falls back to seed data in dev, and the two "create" dialogs (API key, FX quote) validate input then push an info toast without calling the SDK. No 401-refresh path (`refreshAccessToken` exists but is never called).
- **`apps/mobile`** (Expo 57, React Native 0.86, TypeScript 6): a real 7-screen wallet app with end-to-end PKCE via `expo-auth-session`, `bigint`-only money handling (refuses float-unsafe integers), and an idempotency-key-aware API client with 401→refresh→retry. **Not yet workspace-linked** to `packages/ts/sharkpay-client` (types are vendored).

---

## 3. Feature status inventory

Status legend: `IMPLEMENTED_AND_VERIFIED` · `IMPLEMENTED_BUT_UNVERIFIED` · `PARTIALLY_IMPLEMENTED` · `SKELETON_ONLY` · `NOT_IMPLEMENTED` · `BROKEN` · `UNSAFE` · `BLOCKED`.

### 3.1 Ledger (Go) — the financial core

| Capability | Status | Evidence |
|---|---|---|
| True double-entry; Σdebit=Σcredit per currency | IMPLEMENTED_AND_VERIFIED | `domain/balance.go:11-33`; SQL trigger `trg_entry_balanced` (`migrations/001_ledger_init.sql:90-118`); tested `service_test.go:153-180` |
| Wallet non-negativity | IMPLEMENTED_AND_VERIFIED | `domain/balance.go:42-65`; trigger `trg_wallet_non_negative`; tested `:182-202` |
| Journal immutability (append-only) | IMPLEMENTED_AND_VERIFIED | `GRANT SELECT, INSERT` + `REVOKE UPDATE, DELETE` on financial tables (`migrations:169-175`); no UPDATE/DELETE in code |
| Reversal via compensation; double-reversal rejected | IMPLEMENTED_AND_VERIFIED | `domain/reversal.go:39-83`; `UNIQUE(reverses_entry_id) DEFERRABLE`; tested `:289-334` |
| Money as int64 minor units (no floats) | IMPLEMENTED_AND_VERIFIED | `domain/money.go:3-4`; `int64` everywhere; 1e15 bound |
| Concurrency / overspend prevention | IMPLEMENTED_AND_VERIFIED | `SELECT … FOR UPDATE` one-row-at-a-time in ascending UUID order (`storage/postgres.go:195-229`); deadlock-free lock ordering tested `storage/fake_test.go:103-152` |
| Idempotency on `(source, transaction_key)` + payload conflict | IMPLEMENTED_AND_VERIFIED | `UNIQUE(source, transaction_key)`; replay + conflict paths; tested `:114-151` |
| HTTP API + error envelope + opt-in bearer | IMPLEMENTED_AND_VERIFIED | `api/server.go:43-48`; structured domain error codes |
| Request-ID / recover / structured logging | IMPLEMENTED_AND_VERIFIED | `api/server.go` middleware chain |
| Event publishing / transactional outbox | **NOT_IMPLEMENTED** | No NATS/Kafka/outbox anywhere; downstream polls statements |
| `GET /internal/transactions/{id}`, `GET /internal/accounts/{id}` | **NOT_IMPLEMENTED** | Store supports `GetEntry`; API doesn't expose it |
| Live-Postgres integration test in-tree | **NOT_IMPLEMENTED** | 674 lines of SQL exercised only via FakeStore-mirroring unit tests |

### 3.2 Providers (Go) — provider gateway

| Capability | Status | Evidence |
|---|---|---|
| Provider abstraction + registry | IMPLEMENTED_AND_VERIFIED | `internal/provider/provider.go:215-253` |
| HoneyCoin adapter (real HTTP, not mock) | IMPLEMENTED_AND_VERIFIED | `internal/honeycoin/adapter.go`; points at WireMock in dev |
| HMAC-SHA256 outbound signing + adapter idempotency | IMPLEMENTED_AND_VERIFIED | `signing.go`; `X-Idempotency-Key` |
| Circuit breaker (5/30s → OPEN 60s → probe) | IMPLEMENTED_AND_VERIFIED | `internal/health/breaker.go`; tested `breaker_test.go` |
| Callback signature verify (constant-time HMAC) + freshness ±5min + replay 10min | IMPLEMENTED_AND_VERIFIED | `internal/callback/verify.go:178-227`; Redis SET NX atomic |
| Ambiguity contract (UNKNOWN → park+alert, never auto-retry) | IMPLEMENTED_AND_VERIFIED | `provider.go:54-90` |
| Audit-before-return + redaction + integer fidelity | IMPLEMENTED_AND_VERIFIED | `adapter.go:534-717`; regression-tested |
| Router (hard filters + weighted score + deterministic tiebreak) | IMPLEMENTED_AND_VERIFIED | `internal/router/`; tested `router_test.go` (261 lines) |
| Postgres `adapter_calls` audit store | **NOT_IMPLEMENTED** | `MemoryStore` only — non-durable repudiation control |
| Breaker state → router hard-filter propagation | **NOT_IMPLEMENTED** | Registry health set manually once at boot (`main.go:65`) |
| `/readyz`, auth on `/v1/*`, request-ID, structured log | **NOT_IMPLEMENTED** | Thinner envelope than ledger; `http.StatusText(code)` as "code" |
| Kafka event publishing (`providers.transfer.*.v1`) | **NOT_IMPLEMENTED** | Deferred (`main.go:8-9`) |

### 3.3 Java services (identity, wallet, payments, reconciliation — deep; fx, payouts, risk, api-gateway — inventoried)

| Capability | Status | Evidence |
|---|---|---|
| Domain model + invariants (per service) | IMPLEMENTED_AND_VERIFIED | e.g. `wallet/domain/Hold.java:110-120` partial-capture invariant; `payments/domain/PaymentIntent.java` 9-state machine |
| Use-case layer (idempotent on `Idempotency-Key`) | IMPLEMENTED_AND_VERIFIED | `(scope,key)→(fingerprint,entity_id)` in `idempotency_keys`; replay → 200 + `X-Idempotent-Replay`; conflict → 409 |
| Money as `long amountMinor` (no floats) | IMPLEMENTED_AND_VERIFIED | `packages/java/sharkpay-money/Money.java:25-27`; overflow-throws; consumed by 7/8 Java services |
| Cross-service ports (17 stubs) | **BLOCKED** | See §2.3 — all throw `IllegalStateException` |
| Flyway migrations | **NOT_IMPLEMENTED** | `find -name 'V*.sql'` → empty; `ddl-auto=validate` will fail boot on fresh DB |
| Event publishing (real) | **NOT_IMPLEMENTED** | `LoggingEventPublisher` writes to SLF4J in every service; no NATS client in any `pom.xml` |
| Temporal saga (payments) | PARTIALLY_IMPLEMENTED | `PaymentWorkflowImpl` is real + race-tested; `@ConditionalOnProperty temporal.enabled` default `false` |
| Wallet ledger-event consumer (idempotent, leg-level) | IMPLEMENTED_AND_VERIFIED | `ApplyLedgerEventUseCase`; `applied_ledger_events` + `(wallet_id,posting_id)` dedup; out-of-order converges |
| Reconciliation 4-eyes compensation | IMPLEMENTED_AND_VERIFIED | `ApproveAndExecuteCompensationUseCase` rejects approver==requester (`FourEyesException`) |
| Authorization (RBAC) | PARTIALLY_IMPLEMENTED | `SecurityConfig` = Keycloak JWT required, **no role checks**; 4-eyes enforced at use-case level, not Spring Security |
| OTEL / Micrometer / tracing | NOT_IMPLEMENTED | grep `otel\|nats\|micrometer\|tracing` on `application.yml` → 0 hits |

### 3.4 Web console (Next.js)

| Capability | Status | Evidence |
|---|---|---|
| App shell, navigation, 10 route pages | IMPLEMENTED_AND_VERIFIED | `src/app/(console)/` |
| Auth: AES-GCM session + PKCE S256 + mock mode | IMPLEMENTED_AND_VERIFIED | `src/lib/auth/`; tested `session/pkce/keycloak/mock.test.ts` |
| Middleware auth gate (nodejs runtime) | IMPLEMENTED_AND_VERIFIED | `src/middleware.ts:22` (fixed for Vercel services) |
| API client (idempotency, retry, errors, timeout) | IMPLEMENTED_AND_VERIFIED | `src/lib/api/client.ts`; 16 test cases |
| Money (int minor units, bigint-safe) | IMPLEMENTED_AND_VERIFIED | `src/lib/money.ts`; bigint edge tested |
| SDK typed stubs | IMPLEMENTED_BUT_UNVERIFIED | Several modules flag missing contracts (payouts list, fx list, risk, api-keys) |
| Create flows (payment/payout/webhook/API key/FX quote) | **NOT_IMPLEMENTED / SKELETON_ONLY** | Dialogs validate + toast, never call SDK |
| 401 token refresh | **PARTIALLY_IMPLEMENTED** | `refreshAccessToken` exists, never called |
| Developer portal / API docs page | **NOT_IMPLEMENTED** | No `/docs` route |
| Component/view tests | NOT_IMPLEMENTED | Coverage enforced for `src/lib/**` only |

### 3.5 Mobile app (Expo)

| Capability | Status | Evidence |
|---|---|---|
| 7 screens + navigation + session-phase-gated tree | IMPLEMENTED_AND_VERIFIED | `RootNavigator.tsx:77-86` |
| PKCE via `expo-auth-session` | IMPLEMENTED_AND_VERIFIED | `gateway.ts:138-227` |
| bigint-only money (refuses float-unsafe) | IMPLEMENTED_AND_VERIFIED | `money/parse.ts`, `money/format.ts` |
| Idempotency-aware API client (401→refresh→retry) | IMPLEMENTED_AND_VERIFIED | `client.ts:206-390` |
| Workspace link to `@sharkpay/client` | NOT_IMPLEMENTED | Types vendored in `src/api/types.ts` |

### 3.6 Platform / infra / contracts

| Capability | Status | Evidence |
|---|---|---|
| Dev stack (docker-compose, 17 services) | IMPLEMENTED_AND_VERIFIED | All 10 domains + Postgres/NATS/Redis/Keycloak/Temporal/Wiremock |
| Postgres init (schemas + roles) | IMPLEMENTED_AND_VERIFIED | `01-schemas.sql`; creates NO tables (by design) |
| Keycloak realm (10 clients, 19 roles, demo users) | IMPLEMENTED_AND_VERIFIED | `sharkpay-realm.json` |
| Observability overlay (otel+prom+grafana+loki+tempo+alertmanager) | IMPLEMENTED_AND_VERIFIED | 6 dashboards, 23 alerts, 9 runbooks |
| K8s base + dev/staging/prod overlays + Argo CD | IMPLEMENTED_BUT_UNVERIFIED | Manifests only; no cluster, no live deploy; image tags `PLACEHOLDER` |
| OpenAPI 3.1 contracts (v1) | IMPLEMENTED_AND_VERIFIED | 7 files; 5 domains + webhooks public; append-only (ADR 003 §2) |
| CloudEvents contracts | IMPLEMENTED_AND_VERIFIED (doc drift) | 11 JSON schemas; `events.md` still says "Kafka" |
| Proto contracts / gRPC | **NOT_IMPLEMENTED** | No `.proto` files despite ARCHITECTURE.md claim |
| `packages/go/money` | IMPLEMENTED but **UNUSED** | Built + race-clean; no Go service requires it |
| `packages/java/sharkpay-money` | IMPLEMENTED and CONSUMED | 7/8 Java services |
| `packages/ts/sharkpay-client` | IMPLEMENTED but UNUSED internally | Publish-only; both apps build their own clients |
| CI `verify.yml` (G1–G5, fail-closed gate) | IMPLEMENTED_AND_VERIFIED | 6 jobs, stable IDs, CODEOWNERS |
| CI release pipeline (Docker build/push, image promotion) | **NOT_IMPLEMENTED** | No release workflow |
| CI security scanning (CodeQL/Dependabot/Trivy) | **NOT_IMPLEMENTED** | None present |
| CI e2e / live integration | **NOT_IMPLEMENTED** | Compose config-validated only |
| Load tests (k6/gatling) | **NOT_IMPLEMENTED** | PRD §Phase 10 requires 1k TPS |
| Cross-service integration tests | PARTIALLY_IMPLEMENTED | Provider conformance suite (3k lines, in-process fake only) |

---

## 4. Test and build results

**Not executed during this audit** (phase rules forbid running builds/migrations). The following is the *defined* verification surface, taken from `.github/workflows/verify.yml` and `scripts/verify-all.sh`:

- **G4 — contracts:** python3 + pyyaml validates every `contracts/openapi/v1/*.yaml` has `openapi`+`info` and every `contracts/events/*.json` parses.
- **G1+G2+G3 — Go:** per-module (`packages/go/money`, `services/ledger`, `services/providers`) build → vet → gofmt → `go test -cover`. Race detector on `packages/go/money` only.
- **G1+G2+G3 — Java:** sequential `mvn -B -ntp clean verify` per discovered `pom.xml` (sorted so `packages/` build first). JaCoCo `LINE >= 0.80` build-enforced gate.
- **G1+G2 — web:** `npm ci` + `npm run build` + `npm run test` (guarded by `hashFiles('apps/web/package.json')`). **Stale comment** at `verify.yml:226` claims "apps/web does not exist yet" — it does.
- **G5 (config half):** `docker compose config -q` — validates YAML, never builds/executes.
- **gate:** aggregate, fail-closed.

**Test volume observed:** Go ledger ~1,200 test lines; Go providers ~1,485 test lines; Java identity 26 test files, wallet 34, payments 49 (incl. `ConcurrentCreateIsolationTest`, `PaymentWorkflowRaceTest`), reconciliation 42; web 11 test files; mobile 5. The provider conformance suite is 3,024 Go lines.

**Known gaps in verification:** no live-Postgres integration test for the ledger; no Testcontainers for Java JPA; no e2e workflow; no security scanning; no load tests; compose never actually run in CI.

---

## 5. Database and ledger findings

### 5.1 The migration gap (CRITICAL)

`find . -name '*.sql'` returns exactly two files:

1. `infrastructure/dev/postgres/init/01-schemas.sql` (144 lines) — creates 10 LOGIN roles + 9 schemas + 2 temporal databases. **Creates zero tables.** Its own header says: *"It provisions namespaced storage ONLY — never tables. Tables are owned by each service's own migrations."*
2. `services/ledger/migrations/001_ledger_init.sql` (186 lines) — the **only** service migration in the repo. It is excellent: `accounts`, `journal_entries`, `postings` with two deferred CONSTRAINT triggers (`trg_entry_balanced`, `trg_wallet_non_negative`) and an app role `sharkpay_app` with append-only grants.

`find . -name 'V*.sql'` returns **nothing**. All 8 Java services have `spring.flyway.enabled=true` + `jpa.hibernate.ddl-auto=validate` and **zero** `V*.sql` files. **`ddl-auto=validate` will fail with `SchemaValidationException` on startup for every Java service against a fresh database.** Entity javadocs reference migration files that do not exist (e.g. `services/payments/.../storage/PaymentIntentEntity.java:18` → "V1__payments_init.sql"). JPA adapters are tested only via Mockito (`JpaAdaptersTest.java:38` "without a database").

This is the single largest production blocker for the Java platform.

### 5.2 Ledger integrity (strength)

The ledger enforces financial invariants at three layers:
1. **Application** (`domain/balance.go`) — per-currency balance, wallet non-negativity, idempotency key validation.
2. **Storage under locks** (`storage/postgres.go:152-161`) — re-checks invariants while holding `SELECT … FOR UPDATE` in ascending UUID order (dodges PG's multi-row `ORDER BY FOR UPDATE` caveat).
3. **Database** (`migrations/001_ledger_init.sql:90-153`) — deferred CONSTRAINT triggers as a COMMIT backstop, plus `REVOKE UPDATE, DELETE` making the journal append-only at the role level.

Money is `int64` minor units everywhere; no `float64` anywhere. Reversals use deterministic keys (`ops:rev:<id>`) with `UNIQUE(reverses_entry_id) DEFERRABLE` to block double-reversal.

### 5.3 Schema isolation

Each service owns exactly one Postgres schema (`ledger`, `identity`, `wallet`, …) with a dedicated LOGIN role whose `search_path` is pinned. This is good tenant-of-tenant isolation at the schema level. **There is no multi-tenant (organization) column or row-level security in any audited schema** — the platform is single-tenant at the data model level today, despite the target architecture requiring multi-tenancy.

---

## 6. Security findings

### 6.1 Strengths

- **No real secrets committed.** Every secret in-repo is a documented dev-only literal (Keycloak client secrets `*-service-dev-secret`, demo passwords, `dev-honeycoin-key`), consistent across `.env.example`, `docker-compose.yml`, the postgres init, and the Keycloak realm JSON. `.gitignore` is comprehensive (`.env`, `worklog.md`, `.agent-logs/`, build artifacts, tfstate).
- **Money is integer-only everywhere** (Go `int64`, Java `long`, TS `bigint`/`number`-with-`Number.isSafeInteger` guards). No float arithmetic on financial amounts in any audited path. The providers adapter even uses `json.Decoder.UseNumber()` to prevent float64 round-tripping above 2^53 in the audit trail, regression-tested.
- **Auth crypto is correct.** Web: AES-256-GCM with random 12-byte IV, SHA-256 key derivation, null-on-tamper, 30s clock skew. PKCE S256 hand-rolled correctly per RFC 7636 (challenge = base64url(SHA-256(verifier)), tested). Mobile: full `expo-auth-session` PKCE.
- **Callback security (providers) is strong.** Constant-time HMAC-SHA256 verify, freshness ±5min, replay nonce TTL 10min, Redis `SET NX` atomic across pods, fail-closed on cache unavailability.
- **Open-redirect protection** in the web login flow (`sanitizeNextPath` rejects `//` and non-`/` starts).

### 6.2 Weaknesses / risks

| # | Finding | Severity | Evidence |
|---|---|---|---|
| S1 | **No RBAC.** All Java `SecurityConfig` classes require only a valid Keycloak JWT — no role checks. Any authenticated principal can hit any endpoint, including the 4-eyes compensation approval. 4-eyes is enforced only at the use-case layer (`ApproveAndExecuteCompensationUseCase`). | HIGH | `services/payments/.../config/SecurityConfig.java:21-29` + equivalents |
| S2 | **No multi-tenancy at the data layer.** No `tenant_id`/`organization_id` columns, no RLS. The target architecture requires organization-scoped isolation. | HIGH | schema audit |
| S3 | **Providers audit store is memory-only.** `MemoryStore` loses all `adapter_calls` on restart — the SECURITY §1 repudiation control is non-durable. | HIGH | `services/providers/internal/store/store.go` |
| S4 | **`AUTH_SECRET` length check is 16 chars**, not 32 as `.env.example` documents. A 16-char secret under-specifies entropy for the 256-bit AES key. | MEDIUM | `apps/web/src/lib/env.ts:36` |
| S5 | **id_token signature not verified** (web + mobile). Documented as the foundation stance (token arrives over TLS from the token endpoint). Defensible, revisit before production. | MEDIUM | `apps/web/src/lib/auth/keycloak.ts:174-179` |
| S6 | **`Secure` cookie flag depends on `x-forwarded-proto`.** Standard Vercel pattern, but header stripping by a misconfigured proxy would send the cookie over HTTP. | LOW | `apps/web/src/app/api/auth/login/route.ts:22-30` |
| S7 | **No CI security scanning.** No CodeQL/Dependabot/Trivy/Snyk. Notable for a fintech. | MEDIUM | `.github/workflows/` |
| S8 | **Provider `/v1/*` endpoints have no auth** (rely on ingress/mTLS that isn't implemented). | MEDIUM | `services/providers/cmd/server/main.go` |
| S9 | **Temporal has no healthcheck in compose**; payments/payouts use `service_started` not `service_healthy` — may start before Temporal accepts workflows. | LOW | `docker-compose.yml:102-114` |
| S10 | **Java services use TCP-probe healthchecks**, not `/actuator/health` — proves port open, not Spring Boot ready. | LOW | `docker-compose.yml:192-196` |

---

## 7. Integration readiness

**An external application cannot yet securely integrate with SharkPay.** Specifically:

- ❌ **No working public API.** The `api-gateway` service exists with API keys + webhook dispatcher, but `IntegrationPendingUpstream` and `IntegrationPendingEventSource` are fail-fast stubs — it cannot route to backends or emit webhook events.
- ❌ **No end-to-end money movement.** The 17 `IntegrationPending*` stubs mean no payment can complete: payments cannot call risk, wallet (holds), ledger, or providers.
- ❌ **No real webhook delivery.** The api-gateway HMAC dispatcher is implemented, but its event source is a stub.
- ❌ **No NATS event publishing.** Every `EventPublisher` is logging-only.
- ✅ **Contracts exist.** OpenAPI 3.1 for 5 public domains + webhooks; CloudEvents schemas for 11 event types. Append-only (ADR 003 §2).
- ✅ **Idempotency is contract-first.** `Idempotency-Key` header on every mutating endpoint; replay → original response + `X-Idempotent-Replay`; conflict → 409.
- ✅ **Tenant/credential model is designed** (Keycloak realm has S2S clients + `service.<domain>.<read|write>` roles) but **not enforced** in code (S1).

**Product integration readiness (Novera, OruCoin, Fuatilia, Rovanta, MjengoOS):** blocked on the 17 stubs + NATS wiring + api-gateway upstream. No product can integrate until at minimum: api-gateway routes to backends, one payment flow completes end-to-end, and signed webhooks actually fire.

---

## 8. Deployment and operational readiness

| Concern | State |
|---|---|
| Dev stack | ✅ Comprehensive (17 containers, all infra). |
| K8s manifests | ⚠️ Real (base + 3 overlays + Argo CD app-of-apps) but **no cluster, no images** (tags `PLACEHOLDER`); payments/payouts/recon/api-gateway images "do not exist yet" per k8s README. |
| Observability | ✅ Overlay implemented (otel-collector + Prom + Grafana + Loki + Tempo + Alertmanager, 6 dashboards, 23 alerts, 9 runbooks). ⚠️ Services don't emit the metrics the AnalysisTemplates query → canary analysis runs Inconclusive. |
| Release pipeline | ❌ No CI workflow builds/pushes images or promotes tags. |
| Backups / DR | ❌ No backup/restore automation or tested recovery. SECURITY.md references AWS RDS encryption / S3 WORM — none exists in-repo. |
| Temporal | ⚠️ Provisioned in dev; disabled by default in payments (`temporal.enabled=false`); no healthcheck. |
| Secrets in prod | ⚠️ K8s `*.env.example` templates with `REPLACE_ME_*`; real Secrets must be created out-of-band. No managed-secrets/KMS wiring. |

**Production readiness: NOT READY.** The platform cannot serve a single real payment today, primarily because of the 17 stubbed ports, missing Java migrations, no NATS wiring, and no release pipeline.

---

## 9. Evidence-backed defects and risks (prioritized)

### CRITICAL

| ID | Defect | Evidence | Remediation direction |
|---|---|---|---|
| C1 | **No Java Flyway migrations.** 8 services will fail to boot against fresh Postgres (`ddl-auto=validate`). | `find -name 'V*.sql'` → empty; `application.yml` per service | Author + validate `V1__<service>_init.sql` for each Java service against Postgres (Testcontainers). |
| C2 | **17 IntegrationPending* stubs block all money movement.** | §2.3 | Implement REST adapters: payments→{risk,wallet,ledger,providers}; wallet→{identity,ledger}; recon→{providers,ledger}; fx→{rate source,ledger}; payouts→{wallet,identity,ledger,providers}; api-gateway→{upstream,event source}. |
| C3 | **No real event publishing / transactional outbox.** Every `EventPublisher` is logging-only; no NATS client in any `pom.xml`. | `LoggingEventPublisher` in every service | Implement a NATS JetStream `EventPublisher` + transactional outbox; wire wallet's `LedgerEventConsumer` to NATS. |
| C4 | **ADR 002 (polyglot Go+Java split) is "Proposed — pending owner confirmation."** The entire backend strategy rests on an un-accepted ADR. | `docs/adr/002-backend-strategy.md:3` | Get owner sign-off and mark Accepted, or consolidate. |

### HIGH

| ID | Defect | Evidence |
|---|---|---|
| H1 | Doc drift: `ARCHITECTURE.md` says Go/Kafka/gRPC; reality is polyglot/NATS/REST. `events.md` says Kafka. Makefile says redpanda. `verify.yml:226` says web doesn't exist. `BACKEND-DESIGN.md:8` links git-ignored `.agent-logs/`. | §audit |
| H2 | No `.proto` files; "internal gRPC with mTLS" is unimplemented. | grep |
| H3 | No RBAC — JWT-only auth on all Java services. | S1 |
| H4 | No multi-tenancy at data layer (no tenant columns/RLS). | S2 |
| H5 | Providers audit store memory-only (non-durable repudiation control). | S3 |
| H6 | `packages/go/money` is dead code (built, tested, unused). `@sharkpay/client` TS SDK unused internally. | go.mod audit |
| H7 | Breaker state not propagated to providers router hard-filter. | `main.go:65` |
| H8 | No CI release pipeline, no security scanning, no e2e/integration tests, no load tests. | `.github/workflows/` |

### MEDIUM

| ID | Defect |
|---|---|
| M1 | Web: no 401-refresh path; create flows are decorative; no developer portal. |
| M2 | Web: `AUTH_SECRET` 16-char minimum vs documented 32. |
| M3 | Temporal disabled by default + no healthcheck in compose. |
| M4 | Java healthchecks use TCP probe not `/actuator/health`. |
| M5 | Providers HTTP envelope weaker than ledger (no request-ID/recover/`/readyz`/auth; `http.StatusText` as code). |
| M6 | No OTEL/Micrometer/tracing config in any service. |
| M7 | No live-Postgres integration test for ledger; no Testcontainers for Java. |

---

## 10. What is genuinely strong (do not rewrite)

To honor the mandate's "do not rewrite working functionality merely because a different technology is preferred," the following are production-grade and should be preserved:

- **Ledger domain + storage + SQL invariants** — three-layer defense, append-only at the role level, deadlock-free lock ordering, integer money.
- **Providers HoneyCoin adapter** — real integration, HMAC signing, ambiguity contract, audit-before-return with integer fidelity, callback verify, Redis replay cache, circuit breaker.
- **Java hexagonal architecture** — clean `domain/ports/service/storage/api` separation with in-tree fakes; the 17 stubs are a feature (consumer-driven contracts), not a bug, once adapters are wired.
- **Payments Temporal saga** — full workflow with compensation + expiry timer + retry policies + determinism rules; race-tested via `TestWorkflowEnvironment`.
- **Idempotency discipline** — contract-first, every mutating endpoint, payload-conflict detection, leg-level dedup in wallet's ledger consumer.
- **Money discipline** — integer minor units everywhere, no floats, bigint-safe in TS.
- **Web auth crypto + API client** — AES-GCM session, PKCE S256, idempotency-aware client with retry/backoff/timeout.
- **Mobile PKCE + bigint money + 401-refresh API client.**
- **Contracts** — OpenAPI 3.1 + CloudEvents, append-only, versioned.
- **Dev stack + observability overlay + K8s manifests + CI G1–G5 ladder + CODEOWNERS.**

---

## 11. Current production-readiness assessment

**Not production-ready.** The financial core (ledger) is correct and the domain logic across services is mature and tested, but the platform cannot move money end-to-end today. The blockers, in dependency order, are:

1. **Java Flyway migrations** (C1) — without these, no Java service boots against a real database.
2. **The 17 integration adapters** (C2) — without these, no payment completes.
3. **Real event publishing + transactional outbox** (C3) — without these, no downstream learns of state changes reliably and no webhooks fire.
4. **ADR 002 sign-off** (C4) — without this, the polyglot strategy is undecided.
5. **RBAC + multi-tenancy** (H3, H4) — without these, the platform cannot serve multiple organizations safely.
6. **Release pipeline + security scanning + e2e tests** (H8) — without these, no safe production promotion.

With those resolved, SharkPay has the architectural bones to be the shared wallet platform the mandate describes. The work is integration and operationalization, not redesign.

---

*This audit is evidence-based. Every status classification cites a file or a stated limitation. The companion documents `GAP_ANALYSIS.md`, `TARGET_ARCHITECTURE.md`, and `INTEGRATION_CONTRACTS.md` expand the gaps, the target state, and the integration contracts respectively.*
