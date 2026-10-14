# SharkPay — Gap Analysis

> **Phase:** Audit and planning. This document maps every target capability to its current state with evidence, then defines the gap, priority, dependencies, proposed approach, and verification criteria. Read alongside `AUDIT.md` (evidence) and `TARGET_ARCHITECTURE.md` (target state).
> **Priority legend:** P0 = production blocker / safety foundation. P1 = core money movement. P2 = platform & integrations. P3 = advanced.
> **Status legend:** IV = implemented & verified · IU = implemented but unverified · PI = partially implemented · SK = skeleton only · NI = not implemented · BR = broken · US = unsafe · BL = blocked.

---

## Epic A — Platform foundation & architecture

| ID | Capability | Current | Required | Evidence | Gap | Pri | Deps | Approach | Verification |
|---|---|---|---|---|---|---|---|---|---|
| A-01 | Backend strategy ADR | PI (ADR 002 Proposed) | Accepted | `docs/adr/002-backend-strategy.md:3` | Owner sign-off missing | P0 | — | Ratify polyglot Go+Java split; mark ADR 002 Accepted | ADR status = Accepted |
| A-02 | Doc drift cleanup | BR | Consistent | `ARCHITECTURE.md:13,17` (Go/Kafka/gRPC), `events.md`, `Makefile:34` (redpanda), `verify.yml:226` (web missing), `BACKEND-DESIGN.md:8` (git-ignored link), `DATA-MODEL.md:12` (PG16) | 7+ stale claims | P0 | A-01 | Correct all docs to match reality (polyglot/NATS/REST/PG18/web exists) | grep for "Kafka"/"redpanda"/"gRPC" → only intentional historical references |
| A-03 | Proto/gRPC decision | NI | Decided | no `.proto` files; `ARCHITECTURE.md:17` claims gRPC | Unimplemented claim | P0 | A-01 | Adopt REST for S2S (D2); remove gRPC claim or schedule as P3 | `ARCHITECTURE.md` reflects REST |
| A-04 | `packages/go/money` consumption | IU (unused) | Consumed | `services/{ledger,providers}/go.mod` don't require it | Dead shared lib | P2 | — | Alias Go services' local money types to `packages/go/money` | Both `go.mod` require it; local types removed |
| A-05 | `@sharkpay/client` consumption | IU (unused internally) | Workspace-linked in apps | `apps/{web,mobile}/package.json` don't list it | Duplicate clients | P2 | — | Link `@sharkpay/client` in web+mobile; retire vendored SDKs | Both apps import `@sharkpay/client` |
| A-06 | Makefile completeness | PI | All stacks | `Makefile:34` Go-only, stale redpanda | No Java/web/release targets | P2 | — | Add java/web/contracts/release targets | `make help` lists all |
| A-07 | Release pipeline | NI | CI builds+pushes images | no release workflow | No image promotion | P1 | A-04,A-05 | Add `.github/workflows/release.yml`: build → GHCR → tag `sha-<git>` → overlay commit | Image exists in GHCR; prod overlay tag filled |
| A-08 | CI security scanning | NI | CodeQL+Dependabot+Trivy | `.github/workflows/` | None | P1 | — | Add CodeQL + Dependabot + Trivy workflows | Scans run on PR + schedule |

---

## Epic B — Identity, tenants & permissions

| ID | Capability | Current | Required | Evidence | Gap | Pri | Deps | Approach | Verification |
|---|---|---|---|---|---|---|---|---|---|
| B-01 | Identity domain (SharkID/KYC/principals/devices) | IV | Preserve | `services/identity/` 26 tests | — | — | — | — | — |
| B-02 | Identity Flyway migration | NI | `V1__identity_init.sql` | no `V*.sql` | Won't boot on fresh DB | P0 | — | Write migration from entity shapes; validate via Testcontainers | `mvn verify` boots against fresh PG |
| B-03 | Multi-tenancy: `tenant_id` + RLS | NI | All tenant-owned tables | no tenant columns | Single-tenant model | P0 | B-02 | Add `tenant_id` FK + index + RLS policies; include in unique constraints | Cross-tenant query returns 0 rows |
| B-04 | RBAC enforcement | PI (JWT only) | Role checks | `SecurityConfig` no `@PreAuthorize` | Any auth'd user → any endpoint | P0 | B-03 | Add `@PreAuthorize` per endpoint; enforce `service.<domain>.<r/w>` + human roles | Unauthorized role → 403 |
| B-05 | Maker-checker for high-risk ops | PI (use-case only) | Role-separated | recon 4-eyes at use-case layer | Approver role not enforced in Security | P1 | B-04 | `approver` role distinct from `requester`; enforce in `SecurityConfig` | Non-approver → 403 on approve endpoint |
| B-06 | Scoped integration credentials | IV (api-gateway) | Preserve + tenant binding | `services/api-gateway/` API keys | Per-key tenant binding | P1 | B-03 | Bind API key → tenant + scopes | Cross-tenant key → 403 |
| B-07 | Credential rotation/revocation | IV | Preserve | api-gateway | — | — | — | — | — |
| B-08 | Administrative audit trail | PI | Attributable | events logging-only | Privileged actions not durable | P1 | C-03 (outbox) | Persist audit events via outbox | Audit row per privileged action |

---

## Epic C — Financial accounts & ledger

| ID | Capability | Current | Required | Evidence | Gap | Pri | Deps | Approach | Verification |
|---|---|---|---|---|---|---|---|---|---|
| C-01 | Double-entry + immutability + reversals | IV | Preserve | `services/ledger/` | — | — | — | — | — |
| C-02 | Ledger `GET /internal/transactions/{id}`, `GET /internal/accounts/{id}` | NI | Exposed | `Store.GetEntry` exists; API missing | Read endpoints absent | P1 | — | Add 2 GET endpoints | HTTP 200 returns entry/account |
| C-03 | Transactional outbox on ledger | NI | Outbox + NATS publisher | no outbox anywhere | No event publishing | P0 | — | Add `outbox` table; publisher reads + publishes to NATS `ledger.posting.committed.v1` | Event fires iff tx commits |
| C-04 | Live-Postgres integration test | NI | In-tree Testcontainers | FakeStore only | Real PG untested | P1 | — | Add `postgres_test.go` using testcontainers/pg | CI runs against real PG |
| C-05 | Concurrent stress test (real PG) | NI | N-writer stress | FakeStore serializes | Concurrency untested on PG | P1 | C-04 | N-goroutine concurrent InsertTransaction | No deadlocks; invariants hold |
| C-06 | Wallet account provisioning (ledger accounts) | BL | `IntegrationPendingLedgerAccounts` wired | `WalletConfig.java:67` | Wallet can't create ledger accounts | P0 | C-02 | Implement Java `LedgerAccounts` adapter → ledger `POST /internal/accounts` | Wallet create provisions ledger account |
| C-07 | Wallet ledger-event consumer via NATS | PI (HTTP dev endpoint) | NATS consumer | `LedgerEventsController` dev-only | Events via HTTP in prod | P0 | C-03 | NATS subscriber → `ApplyLedgerEventUseCase` | Consumer dedups; out-of-order converges |
| C-08 | Wallet Flyway migration | NI | `V1__wallet_init.sql` | no `V*.sql` | Won't boot | P0 | — | Write migration | `mvn verify` boots |
| C-09 | Wallet `PrincipalLookup` wiring | BL | identity adapter | `IntegrationPendingPrincipalLookup` | Can't verify principal | P0 | B-02 | REST adapter → identity `/internal/v1/principals/{id}` | Wallet resolves principal |

---

## Epic D — Transaction processing

| ID | Capability | Current | Required | Evidence | Gap | Pri | Deps | Approach | Verification |
|---|---|---|---|---|---|---|---|---|---|
| D-01 | Payment state machine + use cases | IV | Preserve | `services/payments/` 49 tests | — | — | — | — | — |
| D-02 | Payments Flyway migration | NI | `V1__payments_init.sql` | `PaymentIntentEntity.java:18` references non-existent file | Won't boot | P0 | — | Write migration | `mvn verify` boots |
| D-03 | `LedgerPort` adapter (payments) | BL | REST to Go ledger | `IntegrationPendingLedgerPort.java:19,27` throws | No ledger posting | P0 | C-02 | Java client → ledger `POST /internal/transactions` + reverse | Payment posts ledger entry |
| D-04 | `WalletHoldPort` adapter (payments) | BL | REST to wallet | `IntegrationPendingWalletHoldPort` | No holds | P0 | C-08,C-09 | Java client → wallet `/internal/holds/*` | Payment places hold |
| D-05 | `RiskPort` adapter (payments) | BL | REST to risk | `IntegrationPendingRiskPort` | No risk eval | P0 | (risk migration) | Java client → risk `/internal/risk/evaluations` | Payment evaluates risk |
| D-06 | `ProviderGatewayPort` adapter (payments) | BL | REST to providers | `IntegrationPendingProviderGateway` | No provider calls | P0 | — | Java client → providers `/v1/initiate` + poll | Payment initiates at provider |
| D-07 | Temporal enabled + healthcheck | PI (disabled) | Enabled in dev/staging | `temporal.enabled=false` default; no healthcheck in compose | Saga doesn't run | P0 | D-03..D-06 | Set `TEMPORAL_ENABLED=true`; add compose healthcheck; `service_healthy` dep | Workflow runs; race tests green |
| D-08 | Idempotency (payments) | IV | Preserve | `idempotency_keys` + `X-Idempotent-Replay` | — | — | — | — | — |
| D-09 | Ambiguity/timeout handling | IV (providers) | Preserve + payments honors it | `provider.go:54-90` | Payments must treat UNKNOWN as uncertain | P1 | D-06 | Payments workflow parks on UNKNOWN status | Timeout → payment = PENDING (not FAILED) |
| D-10 | Payouts Flyway + 4 ports | BL | migration + 4 adapters | `IntegrationPending{WalletHold,Principal,Ledger,Provider}` | Can't move money | P1 | C-08,B-02,D-03,D-04,D-06 | Mirror payments pattern | Payout completes end-to-end |
| D-11 | Refunds/reversals | IV | Preserve | `ReversePaymentUseCase` | — | — | — | — | — |

---

## Epic E — Payment providers

| ID | Capability | Current | Required | Evidence | Gap | Pri | Deps | Approach | Verification |
|---|---|---|---|---|---|---|---|---|---|
| E-01 | Provider abstraction + HoneyCoin adapter | IV | Preserve | `services/providers/` | — | — | — | — | — |
| E-02 | Postgres `adapter_calls` audit store | NI | Durable | `MemoryStore` only | Non-durable repudiation | P0 | — | Implement PG `AuditStore` | Audit survives restart |
| E-03 | Breaker→router propagation | NI | Auto on state change | `main.go:65` manual `SetHealth` | Tripped breaker still routed | P0 | — | Wire `breaker.State()` → `registry.SetHealth` on every transition | OPEN breaker excluded by router |
| E-04 | Providers HTTP hardening | PI | Match ledger bar | no `/readyz`/auth/request-ID/recover; `http.StatusText` as code | Below ledger standard | P1 | — | Add middleware chain + structured error codes | Envelope matches ledger |
| E-05 | Kafka/NATS event publishing | NI | `providers.transfer.*.v1` | deferred (`main.go:8-9`) | No transfer events | P1 | C-03 | Outbox + NATS publisher | Transfer event fires on status change |
| E-06 | Flutterwave assessment | NI | Capability matrix | not started | Second African provider | P3 | E-01 | Assess corridors/fees/settlement; implement adapter if approved | Conformance suite passes |
| E-07 | Wise Platform assessment | NI | Intl transfers | not started | Intl corridor | P3 | E-01 | Assess; implement if approved | Conformance suite passes |
| E-08 | Provider sandbox/simulators | IV (in-tree fake + WireMock) | Preserve | `tests/providers/fake.go` 896 lines | — | — | — | — | — |

---

## Epic F — Multicurrency & international

| ID | Capability | Current | Required | Evidence | Gap | Pri | Deps | Approach | Verification |
|---|---|---|---|---|---|---|---|---|---|
| F-01 | FX domain (quotes/convert/4-leg) | IV | Preserve | `services/fx/` 59 main + 31 test | — | — | — | — | — |
| F-02 | FX Flyway migration | NI | `V1__fx_init.sql` | no `V*.sql` | Won't boot | P0 | — | Write migration | `mvn verify` boots |
| F-03 | FX `RateProvider` adapter | BL | Real rate source | `IntegrationPendingRateProvider` | No rates | P1 | F-02 | Adapter to approved rate source | Quote returns live rate |
| F-04 | FX `LedgerPort` adapter | BL | REST to ledger | `IntegrationPendingLedgerPort` | No FX posting | P0 | D-03 | Reuse payments `LedgerPort` adapter | Conversion posts 4-leg entry |
| F-05 | Currency registry + precision | IV | Preserve | `common.yaml` Currency enum; `packages/*/money` | — | — | — | — | — |
| F-06 | Country/corridor eligibility | NI | Capability registry | hard-coded | No eligibility config | P2 | — | Configurable registry (country × currency × provider × method) | Disallowed corridor → 400 |

---

## Epic G — Settlement & reconciliation

| ID | Capability | Current | Required | Evidence | Gap | Pri | Deps | Approach | Verification |
|---|---|---|---|---|---|---|---|---|---|
| G-01 | Recon domain (comparison/aging/4-eyes) | IV | Preserve | `services/reconciliation/` 42 tests | — | — | — | — | — |
| G-02 | Recon Flyway migration | NI | `V1__recon_init.sql` | no `V*.sql` | Won't boot | P0 | — | Write migration | `mvn verify` boots |
| G-03 | `ProviderStatementPort` adapter | BL | REST to providers | `IntegrationPendingProviderStatement` | No provider statements | P1 | E-04 | Adapter → providers `/v1/providers/{name}/reconcile` | Recon pulls provider lines |
| G-04 | `LedgerStatementPort` adapter | BL | REST to ledger | `IntegrationPendingLedgerStatement` | No ledger statements | P1 | C-02 | Adapter → ledger `GET /internal/accounts/{id}/statement` | Recon pulls ledger lines |
| G-05 | `LedgerPort` (compensation) adapter | BL | REST to ledger | `IntegrationPendingLedgerPort` | No compensation posting | P0 | D-03 | Reuse payments `LedgerPort` adapter | Compensation posts reversal |
| G-06 | Recon exception queue + aging | IV | Preserve | `SweepAgingBreaksUseCase` | — | — | — | — | — |
| G-07 | Daily close + sign-off | PI | Workflow | use cases exist | No scheduled close | P2 | G-03,G-04 | Scheduled recon run + sign-off | Daily run completes |

---

## Epic H — Developer platform & API integration

| ID | Capability | Current | Required | Evidence | Gap | Pri | Deps | Approach | Verification |
|---|---|---|---|---|---|---|---|---|---|
| H-01 | OpenAPI contracts (v1) | IV | Preserve + extend | `contracts/openapi/v1/` 7 files | Missing: risk/api-keys list/webhook deliveries/payouts list/fx list | P1 | — | Append paths (ADR 003 §2 append-only) | Contracts validate; SDKs generate |
| H-02 | api-gateway `Upstream` adapter | BL | Routes to backends | `IntegrationPendingUpstream` | Public edge can't route | P0 | D-02..D-07 | Implement routing to internal service APIs | Public request → backend response |
| H-03 | api-gateway `EventSource` adapter | BL | NATS consumer | `IntegrationPendingEventSource` | No webhook events | P0 | C-03 | NATS subscriber → webhook dispatcher | Webhook fires on event |
| H-04 | api-gateway Flyway migration | NI | `V1__api_gateway_init.sql` | no `V*.sql` | Won't boot | P0 | — | Write migration | `mvn verify` boots |
| H-05 | Webhook HMAC delivery + retry + DLQ | IV | Preserve | api-gateway dispatcher | — | — | — | — | — |
| H-06 | Web create flows (payment/payout/webhook/API key/FX) | SK/NI | Wired to SDK | dialogs toast-only | Read-only console | P2 | H-01,H-02 | Implement create forms calling SDK | Create → backend persists |
| H-07 | Web 401-refresh | PI | Wired | `refreshAccessToken` dead code | 8h hard expiry | P1 | — | Server-side 401-recovery path | Session survives token expiry |
| H-08 | Developer portal | NI | `/docs` route | absent | No API reference UI | P2 | H-01 | Static event catalog + API reference | `/docs` renders |
| H-09 | Web `AUTH_SECRET` ≥32 | PI | 32+ | `env.ts:36` checks ≥16 | Weak-key risk | P1 | — | Tighten to ≥32 | <32 char → throw |
| H-10 | Sandbox/prod separation | IV (api-gateway sandbox) | Preserve | — | — | — | — | — | — |
| H-11 | SDKs (external) | IV (publish-only) | Publish | `packages/ts/sharkpay-client` | Not published | P2 | H-01 | Publish to npm; generate Java/Go SDKs from OpenAPI | SDK installable |

---

## Epic I — Merchant & collection

| ID | Capability | Current | Required | Evidence | Gap | Pri | Deps | Approach | Verification |
|---|---|---|---|---|---|---|---|---|---|
| I-01 | Payment links | NI | Generate + track | absent | — | P3 | H-02 | Link resource + checkout flow | Link → payment |
| I-02 | Invoices | NI | Invoice lifecycle | absent | — | P3 | H-02 | Invoice domain + status tracking | Invoice → paid |
| I-03 | QR payments | NI | Generate/scan | absent | — | P3 | I-01 | QR encode link | QR → payment |
| I-04 | Merchant checkout | NI | Branded checkout | absent | — | P3 | I-01 | Checkout flow | Checkout completes |
| I-05 | Payment matching | NI | Link payment→invoice | absent | — | P3 | I-02 | Match by reference | Payment allocates to invoice |

---

## Epic J — Treasury & advanced

| ID | Capability | Current | Required | Evidence | Gap | Pri | Deps | Approach | Verification |
|---|---|---|---|---|---|---|---|---|---|
| J-01 | Treasury dashboard | NI | Balances/liquidity | absent | — | P3 | C-07 | Read-side treasury views | Dashboard renders live data |
| J-02 | Bulk payouts | PI | Batch + approval | payouts has state machine | No batch UI/approval | P3 | D-10 | Batch endpoint + approval workflow | Batch validates + approves |
| J-03 | Scheduled payments | PI | Scheduler | `LoggingSchedulerPort` (no real scheduler) | No real scheduler | P2 | D-07 | Temporal timer or quartz scheduler | Scheduled payment fires |
| J-04 | Conditional payments / escrow | NI | Hold-based escrow | wallet holds exist | No escrow workflow | P3 | C-08 | Escrow accounts + milestone release (legal model required) | Release conditions enforced |
| J-05 | Stablecoin/digital-asset | NI | Approved custody | absent | Custody partner undecided | P3 | — | Custody integration (AST-007) | On-chain recon matches |

---

## Epic K — Security, risk & compliance

| ID | Capability | Current | Required | Evidence | Gap | Pri | Deps | Approach | Verification |
|---|---|---|---|---|---|---|---|---|---|
| K-01 | RBAC | PI | Role checks | S1 | Any auth'd → any endpoint | P0 | B-04 | `@PreAuthorize` everywhere | Unauthorized → 403 |
| K-02 | Multi-tenancy | NI | tenant_id + RLS | S2 | Single-tenant | P0 | B-03 | See B-03 | Cross-tenant → 0 rows |
| K-03 | Risk domain | IV | Preserve | `services/risk/` 82 main + 36 test | — | — | — | — | — |
| K-04 | Risk Flyway migration | NI | `V1__risk_init.sql` | no `V*.sql` | Won't boot | P0 | — | Write migration | `mvn verify` boots |
| K-05 | Velocity controls | IV | Preserve | risk velocity counters | — | — | — | — | — |
| K-06 | KYC/KYB integration | PI | External provider | identity KYC decision log | No external KYC provider | P2 | B-02 | Adapter to KYC provider | KYC decision from provider |
| K-07 | Sanctions/AML monitoring | NI | Screening | absent | — | P2 | — | Screening service integration | Screened before payout |
| K-08 | Secrets management | PI | KMS/managed | dev literals + `REPLACE_ME` k8s | No managed secrets in prod | P1 | A-07 | K8s ExternalSecrets + KMS | No raw secrets in manifests |
| K-09 | id_token verification | PI | Verify sig | `keycloak.ts:174-179` skipped | Documented stance | P2 | — | Verify id_token signature in prod | Forged token rejected |

---

## Epic L — Reliability & operations

| ID | Capability | Current | Required | Evidence | Gap | Pri | Deps | Approach | Verification |
|---|---|---|---|---|---|---|---|---|---|
| L-01 | Transactional outbox | NI | Outbox per service | absent | C-03 | P0 | — | Outbox table + publisher per service | Event iff commit |
| L-02 | NATS event publishing | NI | Real publisher | `LoggingEventPublisher` everywhere | C-03 | P0 | L-01 | NATS JetStream `EventPublisher` | Events leave JVM |
| L-03 | OTEL/Micrometer/tracing | NI | Emit metrics+traces | 0 hits in `application.yml` | Canary Inconclusive | P1 | — | Add OTel SDK + expose Prometheus | AnalysisTemplate green |
| L-04 | Temporal healthcheck + enable | PI | Healthy + enabled | no healthcheck; disabled | Saga won't run | P0 | D-07 | Add healthcheck; `TEMPORAL_ENABLED=true` | Workflow runs |
| L-05 | Java `/actuator/health` healthchecks | PI | Use actuator | TCP probe in compose | Port≠ready | P1 | — | Switch compose healthcheck to `/actuator/health` | Healthy = Spring ready |
| L-06 | CI e2e/integration | NI | Live tests | compose config-only | No live verification | P1 | L-01 | Testcontainers + compose-up e2e workflow | E2E passes in CI |
| L-07 | Load tests | NI | 1k TPS | PRD §Phase 10; no k6/gatling | No perf evidence | P2 | L-06 | k6 script | 1k TPS sustained |
| L-08 | Backup/restore testing | NI | Tested restore | absent | No DR evidence | P1 | A-07 | Managed PG PITR + restore drill | Restore succeeds |
| L-09 | DR runbook | NI | RPO/RTO doc | absent | — | P2 | L-08 | Document RPO/RTO + runbook | Runbook exists |
| L-10 | Release gates | PI | Enforced | CI gate exists; no release gates | — | P1 | A-07 | Required checks + manual prod sync | Prod sync gated |

---

## Epic M — Product integrations

| ID | Capability | Current | Required | Evidence | Gap | Pri | Deps | Approach | Verification |
|---|---|---|---|---|---|---|---|---|---|
| M-01 | End-to-end payment (golden path) | BL | One full flow | 17 stubs | No payment completes | P0 | D-03..D-07, C-03, C-07 | Wire all ports; run saga against fakes | Payment: create→hold→initiate→post→callback→capture→post→webhook |
| M-02 | Contract tests per product | NI | Per-integration tests | absent | — | P2 | M-01 | Contract test suite per product boundary | Each product's contract passes |
| M-03 | Novera integration | NI | Contract + creds | absent | — | P3 | M-01,M-02 | Define contract; provision tenant+creds | Novera reads wallet via API |
| M-04 | Fuatilia integration | NI | Invoice matching | absent | — | P3 | M-01,M-02 | Collections + payment matching event | Payment matches invoice |
| M-05 | Rovanta integration | NI | Loan repayment | absent | — | P3 | M-01,M-02 | Repayment allocation event | Repayment allocates to loan |
| M-06 | MjengoOS integration | NI | Milestone payout | absent | — | P3 | M-01,M-02 | Milestone-gated payout | Payout on milestone approval |
| M-07 | OruCoin integration | NI | Asset workflow | absent | Custody undecided (J-05) | P3 | J-05 | Approved custody + asset workflow | Asset op via API |

---

## Priority summary (critical path)

**P0 — unblock everything (do first, largely in parallel):**
1. A-01 (ADR 002 sign-off) → A-02 (doc drift) → A-03 (drop gRPC claim)
2. All 8 Java Flyway migrations (B-02, C-08, D-02, F-02, G-02, H-04, K-04, +payouts)
3. C-03 transactional outbox + L-02 NATS publishing (the cross-cutting enabler)
4. The 17 integration adapters (C-06, C-07, C-09, D-03..D-06, D-10, F-03, F-04, G-03..G-05, H-02, H-03)
5. B-03 multi-tenancy + K-01 RBAC (security foundation)
6. D-07/L-04 enable Temporal
7. E-02 durable audit store + E-03 breaker propagation
8. M-01 golden-path end-to-end payment (the proof)

**P1 — operationalize:** A-07 release pipeline, A-08 security scanning, L-03 observability, L-05/L-06/L-08 readiness, H-07 web refresh, E-04/E-05 providers hardening, H-01 contract gaps.

**P2 — platform & first integrations:** A-04/A-05/A-06 cleanup, H-06/H-08 web create flows + portal, F-06 eligibility, G-07 daily close, J-03 scheduler, K-06/K-07 KYC/AML, L-07 load, M-02 contract tests.

**P3 — advanced:** remaining merchant/treasury/escrow/digital-asset/product integrations.

---

*Each row in this document is a candidate GitHub issue (see the issue backlog). Dependencies are explicit; the critical path is the longest chain through the P0 column.*
