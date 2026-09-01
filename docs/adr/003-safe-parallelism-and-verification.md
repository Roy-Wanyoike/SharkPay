# ADR 003: Safe parallel delivery & pre-PR verification protocol

- **Status:** Accepted
- **Date:** 2026-09-01
- **Deciders:** @Roy-Wanyoike
- **Relates to:** ADR 001 (stack lock), ADR 002 (polyglot by domain)

## Context

SharkPay is built by parallel autonomous agents: Wave 1 delivered the Go core
(money, ledger, provider gateway, 81 files, PR #1); Wave 2 builds four Java
services simultaneously (identity, wallet, FX, risk). The owner requires that
**nothing reaches GitHub until everything works and is tested and verified**,
and that parallel workstreams never corrupt each other or the money semantics.

This ADR records the delivery protocol that makes both guarantees structural
rather than aspirational.

## Decision

### 1. Platform pre-staging (the "platform team" function)

All shared seams are built, verified, and committed **before** any parallel
workstream is dispatched. A workstream agent must never need to create or
modify a file another workstream also touches.

Pre-staged for Wave 2:

- `packages/java/sharkpay-money` — the Java money library, a 1:1 port of
  `packages/go/money` (construction always validated; lossless
  largest-remainder allocation; strict no-float parsing; explicit overflow
  errors). Installed to the local repository as
  `com.sharkpay:sharkpay-money:1.0.0`.
- Pinned toolchain: JDK 25.0.4.1 (Temurin), Maven 3.9.16, Spring Boot 4.1.1,
  JUnit 5.13.4, Surefire 3.5.6, JaCoCo 0.8.15. Agents must not deviate.
- Merged contracts in `contracts/openapi/v1` and `contracts/events` — the
  only integration seam between runtimes (per ADR 002).
- A pre-warmed local Maven repository so parallel builds never race on
  dependency downloads.

### 2. Isolation rules for parallel workstreams

1. **One agent = one module = one directory.** An agent writes only inside
   its own `services/<name>/` tree. Shared root files (`docker-compose.yml`,
   `Makefile`, `go.work`, `.github/workflows/ci.yml`, root poms) are owned by
   the integrator exclusively.
2. **Contracts are append-only.** Agents may read any merged contract and may
   add *new* uniquely-named contract files; they may never modify a merged
   contract. Contract changes are an integration-time decision.
3. **No agent touches git.** All branch/commit/push/PR operations are
   centralised in one integrator role. This eliminates force-push races,
   accidental main pushes, and authorship noise.
4. **No agent runs long-lived shared infrastructure** (no local Docker, no
   databases). Anything requiring a live environment is CI-gated, not
   agent-gated.

### 3. Consumer-driven ports with in-tree fakes

Every cross-service dependency inside a workstream is expressed as a **port**
(an interface owned by the consuming service) implemented for tests by an
**in-tree fake**. Examples: `PrincipalLookup`, `LedgerEventConsumer`,
`RateProvider`, `EventPublisher`. Real adapters (REST clients, NATS binding,
Keycloak) are wired at integration time, once, by the integrator.

This is what makes parallelism safe: no workstream blocks on another, no
workstream sees another's half-finished API, and every fake doubles as an
executable specification of the contract the real adapter must satisfy.

### 4. The verification ladder (all gates green before any PR is created)

| Gate | What | Who |
|---|---|---|
| **G1 — Compile** | `mvn -B clean compile` (Java) / `go build ./... && go vet ./...` (Go) per module | Agent, then integrator |
| **G2 — Tests** | Full unit suite green (Surefire / `go test`). Money-safety tests are mandatory wherever money moves: idempotency (same key ⇒ same result, no double effect), non-negative balance invariants, currency-mismatch rejection, overflow rejection, no-float audit (`grep` for `double`/`float` in money paths must be empty) | Agent, then integrator |
| **G3 — Coverage** | JaCoCo bundle line coverage ≥ 80% (Go: `go test -cover` ≥ 80% on domain+service packages), enforced by build failure, not by report | Agent, then integrator |
| **G4 — Contracts** | Event payloads structurally validated against the CloudEvents JSON schemas; OpenAPI handlers match `contracts/openapi/v1` paths and shapes; no merged contract modified (`git diff --stat contracts/` shows additions only) | Agent, then integrator |
| **G5 — Integration rebuild** | From a clean tree the integrator rebuilds **every** module — Go and Java — runs the full cross-runtime matrix, and re-runs the Go suite to prove no regression. Docker Compose is config-validated (`docker compose config -q`) but not executed locally | Integrator only |

**PR creation is permitted only after G1–G5 are green with evidence.** The PR
body must embed the verification matrix (module → tests → coverage). GitHub
Actions CI on the PR is the final independent check before merge.

### 5. PR discipline

- One PR per wave (or per coherent feature), created by the integrator from
  a feature branch; `main` is never pushed to directly.
- PR body carries the evidence matrix; reviewers (or the owner) can audit
  the ladder without re-running everything.
- CI runs the same gates so a green laptop cannot lie about a broken CI.

## Consequences

- **Positive:** parallel agents cannot collide (isolated modules + append-only
  contracts + integrator-owned git); the money semantics are identical across
  runtimes by construction (ported library + parity tests); nothing reaches
  GitHub unverified (ladder gates + CI).
- **Negative / accepted:** real adapters (NATS, Keycloak, REST clients,
  Postgres) are exercised only in CI, not locally; port fakes can drift from
  real adapters — mitigated by contract tests at integration and CI
  Testcontainers runs in later waves.
- **Version pins** may only change via a new ADR or an integrator commit that
  re-runs the full ladder.
