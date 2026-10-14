#!/usr/bin/env python3
"""Create the SharkPay audit-derived GitHub issue backlog.

Avoids duplicating existing issues #21-#54. Creates labels the mandate
requires that don't yet exist, then creates curated issues for the critical
audit gaps. Reads the GitHub token from upload/sharkpush.txt.
"""
import json
import os
import sys
import urllib.request

REPO = "Roy-Wanyoike/SharkPay"
TOKEN_PATH = "/home/z/my-project/upload/sharkpush.txt"

with open(TOKEN_PATH) as f:
    TOKEN = f.readline().strip()

HEADERS = {
    "Authorization": f"token {TOKEN}",
    "Accept": "application/vnd.github+json",
    "Content-Type": "application/json",
}

BASE = f"https://api.github.com/repos/{REPO}"


def api(method, path, body=None):
    url = f"{BASE}{path}"
    data = json.dumps(body).encode() if body is not None else None
    req = urllib.request.Request(url, data=data, headers=HEADERS, method=method)
    try:
        with urllib.request.urlopen(req) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return e.code, json.loads(e.read().decode())


# --- 1. Create missing labels the mandate requires -------------------------------
EXISTING_LABELS = set()
status, data = api("GET", "/labels?per_page=100")
if status == 200:
    EXISTING_LABELS = {l["name"] for l in data}

NEW_LABELS = [
    ("epic", "5319e7", "Epic / parent issue"),
    ("blocked", "b60205", "Blocked by an external dependency or decision"),
    ("needs-architecture", "fbca04", "Requires an architecture decision before implementation"),
    ("needs-external-approval", "d93f0b", "Requires business/security/regulatory approval"),
    ("audit", "0e8a16", "Audit & planning phase work"),
    ("service/payouts", "1d76db", "payouts service"),
]
for name, color, desc in NEW_LABELS:
    if name not in EXISTING_LABELS:
        st, _ = api("POST", "/labels", {"name": name, "color": color, "description": desc})
        print(f"label {name}: {st}")


# --- 2. Issue backlog (curated, de-duplicated vs #21-#54) -----------------------
ISSUES = []

def add(title, body, labels):
    ISSUES.append({"title": title, "body": body, "labels": labels})


add(
    "[audit] ADR 002 (polyglot Go+Java backend strategy) requires owner sign-off",
    """## Business purpose
The entire backend strategy rests on ADR 002, which is currently **Proposed — pending owner confirmation** (`docs/adr/002-backend-strategy.md:3`). ADRs 001 and 003 are both Accepted; ADR 002 is the odd one out. No backend workstream can be considered committed until this is resolved.

## Current-state evidence
- `docs/adr/002-backend-strategy.md:3` — status: Proposed.
- Audit confirmed the repo IS polyglot by implementation: 2 Go services (ledger, providers) + 8 Java 25 / Spring Boot 4.1.1 services (identity, wallet, payments, payouts, fx, risk, api-gateway, reconciliation).
- See `docs/audit/AUDIT.md` §0 and §10.

## Scope
- Decision: accept the polyglot split (Go for core money path; Java/Spring for platform services) — or consolidate to one runtime.
- Update ADR 002 status to Accepted (or supersede it).
- This is a decision issue, not an implementation issue.

## Non-goals
- Migrating services between runtimes (that would be separate issues if decided).

## Dependencies / blockers
- Blocks: all P0 backend integration work (the stub wiring assumes the polyglot seam stays).

## Acceptance criteria
- [ ] ADR 002 marked Accepted or superseded with a documented decision.
- [ ] `docs/architecture/TARGET_ARCHITECTURE.md` §10 reflects the ratified decision.

## Documentation
- Update ADR 002; reference from `TARGET_ARCHITECTURE.md` §2.2.

## Definition of done
- ADR status updated; referenced from the architecture doc.

**Priority:** P0 · **Labels:** needs-architecture, audit, blocked""",
    ["P0", "needs-architecture", "audit", "blocked", "documentation"],
)

add(
    "[audit] Doc drift cleanup — ARCHITECTURE.md, events.md, Makefile, verify.yml",
    """## Business purpose
Multiple docs contradict the implemented code, misleading contributors and integrators. The audit found 7+ stale claims.

## Current-state evidence (file:line)
- `docs/ARCHITECTURE.md:13` — "Microservices in Go" (reality: 8 Java + 2 Go).
- `docs/ARCHITECTURE.md:17` — "Kafka domain events" (reality: NATS JetStream per ADR 001 §3).
- `docs/ARCHITECTURE.md:17` — "internal gRPC with mTLS" (reality: no `.proto` files; REST only).
- `contracts/events/events.md:3-4,13,28-53` — "Kafka domain events" / "Kafka topic" throughout.
- `Makefile:34` — "redpanda" (replaced by NATS).
- `.github/workflows/verify.yml:226` — "apps/web does not exist yet" (full Next.js 16 console exists).
- `docs/BACKEND-DESIGN.md:8` — links `../.agent-logs/reference/adr-003.md` (git-ignored path).
- `docs/DATA-MODEL.md:12` — "PostgreSQL 16+" (ADR 001 pins 18).
- ~20 Java source files reference "NATS/Kafka" in comments.

## Scope
- Correct all stale claims to match reality.
- Decide REST-vs-gRPC (recommend REST for S2S — see TARGET_ARCHITECTURE §10 D2) and reflect it.

## Acceptance criteria
- [ ] `grep -ri "kafka\\|redpanda\\|gRPC"` in docs returns only intentional historical references.
- [ ] ARCHITECTURE.md reflects polyglot + NATS + REST.
- [ ] Makefile/verify.yml comments accurate.
- [ ] BACKEND-DESIGN.md link fixed.

## Dependencies
- Depends on: ADR 002 sign-off (issue above) for the backend-runtime phrasing.

**Priority:** P0 · **Labels:** documentation, audit""",
    ["P0", "documentation", "audit"],
)

add(
    "[infra/database] CRITICAL: Java Flyway migrations for all 8 services (boot blocker)",
    """## Business purpose
All 8 Java services have `spring.flyway.enabled=true` + `jpa.hibernate.ddl-auto=validate` but **ZERO `V*.sql` files exist**. `ddl-auto=validate` will throw `SchemaValidationException` on startup against a fresh database. **No Java service can boot against the dev Postgres today.** This is the single largest production blocker.

## Current-state evidence
- `find . -name 'V*.sql'` → empty.
- Each service `application.yml`: `spring.flyway.enabled: true`, `jpa.hibernate.ddl-auto: validate`.
- `infrastructure/dev/postgres/init/01-schemas.sql:5-7` — "provisions namespaced storage ONLY — never tables."
- `services/payments/.../storage/PaymentIntentEntity.java:18` — references non-existent `V1__payments_init.sql`.
- JPA adapters tested only via Mockito (`JpaAdaptersTest.java:38` "without a database").
- Only `services/ledger/migrations/001_ledger_init.sql` exists (Go, hand-written).

## Scope
Author + validate `V1__<service>_init.sql` for: identity, wallet, payments, payouts, fx, risk, api-gateway, reconciliation. Derive table shapes from the JPA entities (javadocs document them). Include:
- All entity tables with correct types (`BIGINT` for money `amount_minor`), PKs, FKs, unique constraints.
- Idempotency tables: `UNIQUE(tenant_id, scope, idempotency_key)` once multi-tenancy lands (see tenancy issue).
- Append-only audit tables (e.g. `payment_state_transitions`).

## Non-goals
- Multi-tenancy columns (separate issue).
- Data migrations (fresh-init only for V1).

## Dependencies
- Blocks: every Java service from booting; blocks all integration work.

## Required tests
- Testcontainers-based integration test per service: boot against fresh PG → `ddl-auto=validate` passes → basic CRUD.
- Add to CI `java` job (currently Mockito-only).

## Security/financial considerations
- Money columns MUST be `BIGINT` (integer minor units), never `FLOAT`/`REAL`.
- Append-only tables (journal, transitions) should `REVOKE UPDATE, DELETE` from the app role (mirror ledger pattern).

## Acceptance criteria
- [ ] `V1__<service>_init.sql` exists for all 8 Java services.
- [ ] Each service boots against a fresh Postgres with `ddl-auto=validate`.
- [ ] Testcontainers integration test per service passes in CI.

**Priority:** P0 · **Labels:** infra, P0, service/identity, service/wallet, service/payments, service/payouts, service/fx, service/risk, service/api-gateway, service/reconciliation""",
    ["P0", "infra", "service/identity", "service/wallet", "service/payments",
     "service/payouts", "service/fx", "service/risk", "service/api-gateway",
     "service/reconciliation"],
)

add(
    "[audit] Wire the 17 IntegrationPending* cross-service ports (integration wall)",
    """## Business purpose
17 cross-service ports are fail-fast stubs that throw `IllegalStateException` on every call. No payment can complete end-to-end until these are real REST adapters. This is the integration wall.

## Current-state evidence (full list in `docs/audit/AUDIT.md` §2.3)
- payments (4): `IntegrationPendingRiskPort`, `IntegrationPendingWalletHoldPort`, `IntegrationPendingLedgerPort`, `IntegrationPendingProviderGateway` (`PaymentsConfig.java:90-105`).
- wallet (2): `IntegrationPendingPrincipalLookup`, `IntegrationPendingLedgerAccounts` (`WalletConfig.java:62-68`).
- reconciliation (3): `IntegrationPendingProviderStatement`, `IntegrationPendingLedgerStatement`, `IntegrationPendingLedgerPort` (`ReconConfig.java:77-88`).
- fx (2): `IntegrationPendingRateProvider`, `IntegrationPendingLedgerPort`.
- payouts (4): `IntegrationPendingWalletHoldPort`, `IntegrationPendingPrincipalLookup`, `IntegrationPendingLedgerPort`, `IntegrationPendingProviderGateway`.
- api-gateway (2): `IntegrationPendingUpstream`, `IntegrationPendingEventSource`.

## Scope
Implement each stub as a REST client calling the target service's `/internal/...` API. The Go ledger internal API (`POST /internal/transactions`, reverse, statement, accounts) is the canonical Go↔Java seam. Each adapter is independently deliverable — child issues should be filed per service if this issue is too large.

## Non-goals
- gRPC (recommend REST — see TARGET_ARCHITECTURE §10 D2).
- The event-source adapters (api-gateway `EventSource`, wallet ledger consumer) depend on NATS wiring (separate issue).

## Dependencies
- Depends on: Java Flyway migrations (services must boot).
- Depends on: ledger `GET /internal/transactions/{id}` + `GET /internal/accounts/{id}` (separate issue) for some adapters.

## Required tests
- Each adapter: contract test against the target service's real `/internal` endpoint (Testcontainers or dev stack).
- Idempotency + error-envelope handling.

## Acceptance criteria
- [ ] Zero `IntegrationPending*` classes remain in `src/main`.
- [ ] A payment completes: payments → risk → wallet(hold) → providers(initiate) → ledger(post) → callback → wallet(capture) → ledger(post).
- [ ] Contract tests pass for each adapter.

**Priority:** P0 · **Labels:** P0, audit, service/payments, service/wallet, service/reconciliation, service/fx, service/payouts, service/api-gateway""",
    ["P0", "audit", "service/payments", "service/wallet", "service/reconciliation",
     "service/fx", "service/payouts", "service/api-gateway", "needs-architecture"],
)

add(
    "[infra/events] NATS JetStream EventPublisher + transactional outbox for all services",
    """## Business purpose
Every service's `EventPublisher` is a `LoggingEventPublisher` that writes CloudEvents to SLF4J only. No NATS client exists in any `pom.xml`. Events never leave the JVM. This breaks reliable webhook delivery, wallet balance projection (currently via a dev HTTP endpoint), and all product integrations.

Related existing issue: #44 (ledger outbox) — this issue generalizes to ALL services.

## Current-state evidence
- `LoggingEventPublisher` in every service (e.g. `services/payments/.../config/LoggingEventPublisher.java:20-24`).
- No `nats`/`jetstream` dependency in any `pom.xml`.
- Wallet consumes ledger events only via dev `/internal/ledger-events` HTTP endpoint (`LedgerEventsController.java:32`), not NATS.
- CloudEvent envelopes are correctly built + JSON-schema-validated in tests — only the transport is missing.

## Scope
1. Add a `transactional_outbox` table per service (committed in the same DB tx as the financial change).
2. Implement an outbox→NATS JetStream publisher (separate worker or in-process poller).
3. Replace every `LoggingEventPublisher` with a real `NatsEventPublisher`.
4. Wire wallet's `LedgerEventConsumer` to subscribe to `ledger.posting.committed.v1` on NATS (retire the dev HTTP endpoint for prod).
5. Wire api-gateway's `EventSource` to NATS for webhook dispatch.

## Non-goals
- Kafka (NATS is the locked choice — ADR 001 §3).

## Dependencies
- Depends on: Java Flyway migrations (outbox table).
- Blocks: webhook delivery, product integrations, wallet live balance projection.

## Required tests
- Event fires IFF the DB tx commits (rollback → no event).
- Consumer dedup on event id; out-of-order delivery converges (wallet's leg-level dedup is the reference).
- At-least-once delivery with redelivery.

## Financial correctness
- Invariant #10: financial DB change + outbox row committed atomically.
- Invariant #9: every consumer safe under duplicate delivery.

## Acceptance criteria
- [ ] `transactional_outbox` table per service.
- [ ] `NatsEventPublisher` replaces `LoggingEventPublisher` in all services.
- [ ] Wallet ledger consumer reads from NATS.
- [ ] Event-publishes-on-commit test passes; rollback → no event.

**Priority:** P0 · **Labels:** P0, infra, service/ledger, service/wallet, service/payments, service/api-gateway""",
    ["P0", "infra", "service/ledger", "service/wallet", "service/payments",
     "service/api-gateway"],
)

add(
    "[security] RBAC enforcement in all Java SecurityConfig (currently JWT-only)",
    """## Business purpose
All Java `SecurityConfig` classes require only a valid Keycloak JWT — **no role checks**. Any authenticated principal can hit any endpoint, including the 4-eyes compensation approval. The 4-eyes rule is enforced only at the use-case layer (`ApproveAndExecuteCompensationUseCase`), not by Spring Security.

## Current-state evidence
- `services/payments/.../config/SecurityConfig.java:21-29` (and equivalents in identity/wallet/reconciliation/fx/risk/payouts/api-gateway) — JWT required, no `@PreAuthorize`.
- `CompensationsController.java:37` — approve endpoint has no role gate.
- Keycloak realm already models roles: human (`ops`, `merchant`, `agent`) + S2S (`service.<domain>.<read|write>`).

## Scope
- Add method-level `@PreAuthorize` to every controller endpoint enforcing the appropriate role.
- Human roles: `platform-admin`, `tenant-admin`, `operator`, `approver`, `auditor`.
- S2S: `service.<domain>.<read|write>` on internal endpoints.
- 4-eyes: `approver` role distinct from `requester`; enforce on compensation approve.

## Non-goals
- Multi-tenancy enforcement (separate issue — but role checks should also verify tenant ownership).

## Dependencies
- Depends on: multi-tenancy issue (tenant scoping) for full coverage.

## Required tests
- Unauthorized role → 403 on each protected endpoint.
- Cross-tenant access → 403.

## Acceptance criteria
- [ ] Every controller endpoint has an authorization rule.
- [ ] Unauthorized role → 403 (tested).
- [ ] 4-eyes approve rejected for non-approver role.

**Priority:** P0 · **Labels:** P0, security, service/identity, service/wallet, service/payments, service/payouts, service/fx, service/risk, service/api-gateway, service/reconciliation""",
    ["P0", "security", "service/identity", "service/wallet", "service/payments",
     "service/payouts", "service/fx", "service/risk", "service/api-gateway",
     "service/reconciliation"],
)

add(
    "[security/tenancy] Multi-tenancy: tenant_id + Row-Level Security across all tenant-owned tables",
    """## Business purpose
The platform is single-tenant at the data model level today — no `tenant_id`/`organization_id` columns, no RLS. The target architecture requires multiple organizations (Novera, Rovanta, Fuatilia, MjengoOS, OruCoin, third parties) to share the platform with strict isolation. Tenancy must be enforced in the backend, not merely hidden in the frontend.

## Current-state evidence
- No `tenant_id` column in any audited schema (ledger, wallet, payments, reconciliation).
- No RLS policies.
- API keys (api-gateway) have no tenant binding.

## Scope
1. Add `tenant_id` (NOT NULL FK to `organizations`) + index to every tenant-owned table (wallets, holds, payments, payouts, fx quotes, risk cases, recon runs/breaks, api keys, webhook endpoints).
2. Include `tenant_id` in unique constraints that span tenant-owned rows (e.g. idempotency `UNIQUE(tenant_id, scope, key)`).
3. Application-layer: every query filters by the caller's tenant (resolved from JWT / API key).
4. PostgreSQL RLS as defense-in-depth: `SET LOCAL app.tenant_id = ?` per session; policies filter all tenant-owned tables.
5. Background jobs + event consumers carry + re-assert tenant context.
6. Provision an `organizations` table + tenant provisioning API (relates to existing #41).

## Non-goals
- Replacing app authorization with RLS (RLS is defense-in-depth only).

## Dependencies
- Depends on: Java Flyway migrations (add columns there or a follow-up migration).
- Blocks: any multi-org production use.

## Required tests
- Cross-tenant query returns 0 rows (app + RLS).
- Cross-tenant API key → 403.
- Background job with wrong tenant context → no write.

## Acceptance criteria
- [ ] `tenant_id` on all tenant-owned tables.
- [ ] RLS policies active; cross-tenant SELECT returns 0 rows even bypassing app filters.
- [ ] API key bound to tenant; cross-tenant → 403.

**Priority:** P0 · **Labels:** P0, security, infra, service/wallet, service/payments""",
    ["P0", "security", "infra", "service/wallet", "service/payments",
     "service/api-gateway"],
)

add(
    "[service/providers] Durable Postgres adapter_calls audit store (repudiation control)",
    """## Business purpose
The providers audit store is memory-only (`MemoryStore`). It loses all `adapter_calls` on restart — the SECURITY §1 repudiation control is non-durable in any deployment today.

## Current-state evidence
- `services/providers/internal/store/store.go` — only `MemoryStore` ships; docstrings say production uses PG `adapter_calls` but no PG impl exists.
- Audit-before-return invariant is otherwise strong (`adapter.go:534-717`, regression-tested).

## Scope
- Implement a Postgres `AuditStore` (table `adapter_calls` with the `AdapterCall` shape).
- Wire it as the default when `DATABASE_URL`/`LEDGER-style` DB config is present; keep `MemoryStore` for tests.

## Required tests
- Audit row persists across restart.
- Integer fidelity preserved (existing regression test must pass against PG store).

## Acceptance criteria
- [ ] `adapter_calls` table in the providers schema.
- [ ] Postgres `AuditStore` is the production default.
- [ ] Audit survives restart; redaction + integer-fidelity regression tests pass.

**Priority:** P0 · **Labels:** P0, service/providers, security""",
    ["P0", "service/providers", "security"],
)

add(
    "[service/providers] Breaker state → router hard-filter propagation",
    """## Business purpose
A provider whose circuit breaker opens is still routed to, because registry `HealthState` is set manually once at boot (`main.go:65`). A tripped breaker must exclude the provider from routing.

## Current-state evidence
- `services/providers/cmd/server/main.go:65` — `_ = registry.SetHealth(honeycoin.ProviderName, provider.HealthHealthy)` once at boot.
- `internal/health/breaker.go` — breaker state transitions are not propagated to the registry.
- Router hard-filters on `HealthState` (`internal/router/`).

## Scope
- On every breaker state transition, call `registry.SetHealth(name, mappedState)`.
- Map breaker OPEN → `HealthOpen` (excluded by router), HALF-OPEN → `HealthDegraded`, CLOSED → `HealthHealthy`.

## Required tests
- Open a provider's breaker (5 failures) → router excludes it → `ErrNoCandidate` or fail-over to alternative.
- Breaker recovers → provider re-admitted.

## Acceptance criteria
- [ ] Breaker state transitions propagate to registry health.
- [ ] OPEN breaker → router excludes provider (tested).

**Priority:** P0 · **Labels:** P0, service/providers""",
    ["P0", "service/providers"],
)

add(
    "[service/payments] Enable Temporal + add compose healthcheck (saga doesn't run)",
    """## Business purpose
The payments Temporal saga is real + race-tested but disabled by default (`temporal.enabled=false`). The workflow does NOT run; `PaymentLifecyclePort` = `LoggingPaymentLifecycle`. Also Temporal has no healthcheck in compose, so payments/payouts may start before Temporal accepts workflows.

## Current-state evidence
- `TemporalWorkerConfig.java:35` — `@ConditionalOnProperty(prefix="temporal", name="enabled", havingValue="true")`.
- `application.yml:41` — `temporal.enabled: ${TEMPORAL_ENABLED:false}`.
- `docker-compose.yml:102-114` — `temporal` service has no `healthcheck`.
- payments/payouts use `condition: service_started` (not `service_healthy`) for temporal.

## Scope
- Add a Temporal healthcheck to compose (TCP `:7233` or gRPC health probe).
- Switch payments/payouts temporal dependency to `service_healthy`.
- Set `TEMPORAL_ENABLED=true` in dev + staging.
- Keep `false` only for unit-test profiles.

## Dependencies
- Depends on: the 4 payments IntegrationPending* adapters being wired (otherwise the saga calls fail-fast stubs).

## Required tests
- Workflow runs end-to-end against fakes once enabled.
- Existing `PaymentWorkflowRaceTest` remains green.

## Acceptance criteria
- [ ] Temporal healthcheck in compose; payments/payouts wait for `service_healthy`.
- [ ] `TEMPORAL_ENABLED=true` in dev/staging.
- [ ] A payment saga executes (once adapters wired).

**Priority:** P0 · **Labels:** P0, service/payments, infra""",
    ["P0", "service/payments", "infra"],
)

add(
    "[service/ledger] Expose GET /internal/transactions/{id} + GET /internal/accounts/{id}",
    """## Business purpose
The ledger `Store.GetEntry` exists but no HTTP endpoint exposes it. Downstream services (payments, reconciliation, wallet) need to query a single transaction/account by id. Currently they can only paginate statements.

## Current-state evidence
- `services/ledger/internal/domain/store.go` — `GetEntry` in the interface.
- `services/ledger/internal/api/server.go:43-48` — only `POST /internal/transactions`, reverse, `GET /internal/accounts/{id}/statement`, `POST /internal/accounts`.

## Scope
- Add `GET /internal/transactions/{id}` → entry + postings.
- Add `GET /internal/accounts/{id}` → account metadata.
- Same error envelope + bearer auth as existing endpoints.

## Required tests
- Existing `server_test.go` extended with the two new endpoints (happy + 404).

## Acceptance criteria
- [ ] Both endpoints return 200 with correct payloads; 404 on unknown id.

**Priority:** P1 · **Labels:** P1, service/ledger""",
    ["P1", "service/ledger"],
)

add(
    "[infra/ci] Release pipeline — Docker build/push + image tag promotion",
    """## Business purpose
No CI workflow builds Docker images, pushes to a registry, or promotes image tags into K8s overlays. The GitOps flow (CI → image tag → overlay commit → Argo CD sync) is documented but not automated. Prod overlays have `PLACEHOLDER` image tags.

## Current-state evidence
- `.github/workflows/verify.yml` — build/test only; no release workflow.
- `infrastructure/k8s/README.md:188-199` — "payments/payouts/recon/api-gateway images do not exist yet; manifests are wired and waiting for tags."

## Scope
- `.github/workflows/release.yml`: on tag or merge to main, build each service image → push to GHCR with `sha-<git>` + `latest-dev` tags.
- Commit the `sha-<git>` tag into the appropriate K8s overlay (dev) via a bot PR.
- Argo CD auto-syncs dev; staging follows; prod waits for manual sync.

## Dependencies
- Depends on: services being buildable (Java migrations for boot; not strictly for image build).

## Required tests
- Image builds for all 10 services.
- dev overlay tag updates on merge.

## Acceptance criteria
- [ ] `release.yml` builds + pushes all 10 service images to GHCR.
- [ ] dev overlay image tags auto-update on merge to main.

**Priority:** P1 · **Labels:** P1, infra""",
    ["P1", "infra"],
)

add(
    "[infra/ci] Security scanning — CodeQL, Dependabot, Trivy",
    """## Business purpose
No security scanning in CI. Notable for a fintech.

## Current-state evidence
- `.github/workflows/` — only `verify.yml`; no CodeQL/Dependabot/Trivy/Snyk.

## Scope
- CodeQL workflow (Go + Java + TS).
- Dependabot config (gomod, maven, npm, docker, github-actions).
- Trivy image scan in the release pipeline (block on CRITICAL).

## Acceptance criteria
- [ ] CodeQL runs on PR + schedule.
- [ ] Dependabot PRs open for outdated deps.
- [ ] Trivy scans built images; CRITICAL fails the release.

**Priority:** P1 · **Labels:** P1, security, infra""",
    ["P1", "security", "infra"],
)

add(
    "[infra/observability] Services emit OTEL + Prometheus metrics (canary AnalysisTemplates green)",
    """## Business purpose
The observability overlay (otel-collector + Prom + Grafana + Loki + Tempo + Alertmanager, 6 dashboards, 23 alerts, 9 runbooks) is implemented, but services don't emit the metrics/traces the K8s AnalysisTemplates query. Canary analysis runs Inconclusive, not green — blocking safe production rollout.

## Current-state evidence
- `grep "otel|nats|micrometer|tracing"` on `services/**/application.yml` → 0 hits.
- Only `management.endpoints.web.exposure.include: health,info`.
- K8s AnalysisTemplates query success-rate + p99.

## Scope
- Add OpenTelemetry SDK + Micrometer to each Java service; OTLP exporter to collector.
- Add Go OTel SDK to ledger + providers.
- Emit: payment latency, failed transactions, provider availability, recon backlog, workflow failures, ledger posting rate.
- Propagate `X-Request-Id` / trace context across services.

## Acceptance criteria
- [ ] Each service emits OTel traces + Prometheus metrics.
- [ ] K8s canary AnalysisTemplates go green (not Inconclusive) in dev.

**Priority:** P1 · **Labels:** P1, infra""",
    ["P1", "infra"],
)

add(
    "[infra/test] Live-Postgres integration tests + Testcontainers for Java JPA",
    """## Business purpose
Ledger has 674 lines of SQL exercised only via FakeStore-mirroring unit tests (no live-PG test in-tree). Java JPA adapters are tested only via Mockito ("without a database"). Schema drift is not caught.

## Current-state evidence
- `services/ledger/internal/storage/fake.go` — in-process mirror.
- `services/payments/.../storage/JpaAdaptersTest.java:38` — "without a database".

## Scope
- Ledger: `postgres_test.go` using testcontainers/pg; run in CI `go` job.
- Java: Testcontainers integration test per service alongside Flyway migration (validates ddl-auto=validate + CRUD).

## Acceptance criteria
- [ ] Ledger live-PG test runs in CI.
- [ ] Each Java service has a Testcontainers test that boots against fresh PG.

**Priority:** P1 · **Labels:** P1, infra, service/ledger""",
    ["P1", "infra", "service/ledger"],
)

add(
    "[audit] Golden-path end-to-end payment test (proof of platform)",
    """## Business purpose
No payment can complete end-to-end today (17 stubs). This issue tracks the integration proof: once the stubs + Temporal + outbox are wired, a single payment must flow through every service. This is the success criterion for P0.

## Current-state evidence
- `docs/audit/AUDIT.md` §11 — "the platform cannot move money end-to-end today."

## Scope
A single end-to-end test (in CI, against the dev stack or Testcontainers) that exercises:
1. `POST /v1/payment-intents` (via api-gateway with a scoped API key).
2. payments → risk (evaluate) → wallet (place hold) → providers (initiate) → ledger (post hold).
3. Provider callback → providers verify → payments (record provider result) → wallet (capture hold) → ledger (post capture).
4. NATS event `payments.payment.succeeded.v1` → api-gateway → webhook to a test consumer.
5. Reconciliation run matches internal vs provider.

## Dependencies
- Depends on: ALL P0 issues (Java migrations, 17 stubs, NATS/outbox, Temporal enable, RBAC/tenancy).
- This is the capstone P0 issue.

## Required tests
- The golden-path test itself (this IS the test).
- Concurrent duplicate idempotency-key test (already exists: `ConcurrentCreateIsolationTest`).

## Acceptance criteria
- [ ] Golden-path test passes in CI.
- [ ] Webhook fires with valid HMAC signature.
- [ ] Reconciliation matches.

**Priority:** P0 · **Labels:** P0, audit""",
    ["P0", "audit", "infra"],
)

add(
    "[surface/web] Wire create flows + 401-refresh + AUTH_SECRET≥32 + developer portal",
    """## Business purpose
The web console is a read-only dashboard: every API call falls back to seed data; the two "create" dialogs validate + toast without calling the SDK; `refreshAccessToken` is dead code (8h hard expiry); `AUTH_SECRET` allows 16 chars (docs say 32); no developer portal.

## Current-state evidence
- `NewApiKeyDialog.tsx:41-48`, `NewQuoteDialog.tsx:25-60` — toast-only, no SDK call.
- `apps/web/src/lib/auth/keycloak.ts:152-162` — `refreshAccessToken` exists, never called (grep confirmed).
- `apps/web/src/lib/env.ts:36` — `secret.length >= 16`.
- No `/docs` route.

## Scope
- Implement create flows: payment, payout, webhook endpoint (SDK methods exist: `createPayment`, `createPayout`, `createWebhookEndpoint`); wire the two existing dialogs to actually call the SDK.
- Server-side 401-recovery: on `ApiError.isUnauthorized`, refresh token, retry once with same `Idempotency-Key`.
- Tighten `getAuthSecret` to `>= 32` chars; align with `.env.example`.
- Add `/docs` developer portal (static event catalog + API reference + "copy base URL").

## Dependencies
- Depends on: api-gateway `Upstream` adapter (so live data flows) + missing OpenAPI paths (payouts list, fx list, risk, api-keys).

## Acceptance criteria
- [ ] Create-payment/payout/webhook flows persist via the API.
- [ ] Session survives token expiry (401 → refresh → retry).
- [ ] `AUTH_SECRET` < 32 chars → throw.
- [ ] `/docs` renders the event catalog + API reference.

**Priority:** P1 · **Labels:** P1, surface/web""",
    ["P1", "surface/web"],
)

add(
    "[infra/packages] Consume packages/go/money + @sharkpay/client (dead/duplicated code)",
    """## Business purpose
`packages/go/money` is built, tested, race-clean — but consumed by NO Go service (both define local money types). `@sharkpay/client` TS SDK is publish-only; both apps build their own clients, duplicating the SDK.

## Current-state evidence
- `services/ledger/go.mod:5-8` + `services/providers/go.mod:5-9` — neither requires `packages/go/money`.
- `services/providers/internal/provider/provider.go:25` — comment: "mirrors packages/go/money; keeps a local definition until the shared package lands."
- `apps/web/package.json` + `apps/mobile/package.json` — neither lists `@sharkpay/client`.

## Scope
- Alias Go services' local money types to `packages/go/money`.
- Workspace-link `@sharkpay/client` in web + mobile; retire vendored SDKs (`apps/web/src/lib/api/sdk/*`, `apps/mobile/src/api/types.ts`).

## Acceptance criteria
- [ ] Both Go `go.mod` require `packages/go/money`; local money types removed.
- [ ] Both apps import `@sharkpay/client`; vendored SDKs removed.
- [ ] `verify.yml` green.

**Priority:** P2 · **Labels:** P2, infra, service/ledger, service/providers, surface/web, surface/mobile""",
    ["P2", "infra", "service/ledger", "service/providers", "surface/web", "surface/mobile"],
)

# --- 3. Create issues ----------------------------------------------------------
print(f"\nCreating {len(ISSUES)} issues...\n")
created = []
for iss in ISSUES:
    st, data = api("POST", "/issues", iss)
    if st == 201:
        num = data["number"]
        url = data["html_url"]
        created.append((num, iss["title"], url))
        print(f"#{num}  {iss['title']}")
    else:
        print(f"FAIL [{st}] {iss['title']}: {data.get('message','')}")
        if "errors" in data:
            print(f"       errors: {data['errors']}")

print(f"\nCreated {len(created)} issues.")
# Write a summary file for the PR
with open("/tmp/created_issues.txt", "w") as f:
    for num, title, url in created:
        f.write(f"- #{num} {title} — {url}\n")
print("Summary written to /tmp/created_issues.txt")
