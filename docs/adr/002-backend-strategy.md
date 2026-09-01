# ADR 002: Backend implementation strategy — polyglot by domain, failover at the infrastructure layer

- **Status:** Proposed (pending owner confirmation — merge to accept)
- **Date:** 2026-09-01
- **Deciders:** @Roy-Wanyoike
- **Supersedes:** none · **Relates to:** ADR 001 (stack lock)

## Context

A complete Go core is already merged and green (PR #1: money library, double-entry
ledger, provider gateway + HoneyCoin adapter — 80–96% test coverage). ADR 001 locks
the platform stack as Java 25 + Spring Boot for the service platform.

A second option was raised: **maintain two complete backends (Go and Java), kept
feature-identical and updated in lockstep, and rewire traffic between them when one
is down or consuming excessive resources.**

## Decision (proposed)

**Polyglot by domain — every capability exists exactly once.** Failover and resource
relief are implemented at the infrastructure layer, not by duplicating the backend in
a second language.

### 1. Language assignment

| Domain | Runtime | Rationale |
|---|---|---|
| Ledger core (WP-3), provider gateway + HoneyCoin (WP-4), money library | **Go** | Already merged & tested; TPS-critical hot path on the resource-efficient runtime; ledger semantics change rarely once correct |
| Identity, wallet, payments orchestration, payouts, FX, risk, API platform, recon (WP-1/2/5/6/7/8/9/10) | **Java 25 + Spring Boot** | Platform build-out per ADR 001; first-class Temporal Java SDK, Resilience4j, Flyway |

### 2. Ownership boundaries (no contended shared state)

- **DB-per-service.** Go services own their schemas via golang-migrate (ledger DDL +
  invariant triggers already written); Java services own theirs via Flyway. No schema
  has two migration pipelines.
- **Temporal namespaces are per-runtime.** A workflow is registered and continued by
  exactly one implementation — no cross-language workflow handoff.
- **Contracts are the seam.** Cross-runtime communication is REST (OpenAPI 3.1) and
  CloudEvents on NATS JetStream. Both runtimes produce/consume the same schemas in
  `contracts/`.

### 3. The "rewire" (what actually delivers failover + resource relief)

- **Active-active replicas** of every service across ≥2 AZs; HPA for resource spikes.
- **API gateway** (Spring Cloud Gateway / Kong) with health-based routing, per-service
  traffic weights, and per-rail kill switches — the rewire knob.
- **Blue/green + Argo Rollouts canary** with SLO-based auto-rollback: a bad deploy
  never takes the whole backend at once.
- Because failover shifts *deployments of one implementation*, it is instant (no
  in-flight drain) and cannot diverge semantically.

## Alternatives considered

### A. Permanent dual backends (Go + Java, lockstep, language-level failover) — REJECTED

1. **2× implementation tax forever** — every WP, bugfix, migration, and event consumer
   built twice; review and on-call burden doubles.
2. **Semantic drift = money discrepancies** — two independent implementations of fees,
   FX rounding, idempotency scoping, and hold/release diverge in edge cases. Both
   passing their own test suites does not prove they agree with each other; rewiring
   to a diverged standby produces reconciliation breaks.
3. **Correlated failure** — lockstep updates share the change failure domain: a bad
   migration or contract change ships to both and both fall together. This defeats the
   stated purpose of the redundancy.
4. **Contended shared state** — Temporal workflows are language-bound (in-flight
   payments would need drain windows mid-incident); JetStream consumer groups and
   migration ownership would need single-sourcing anyway.
5. **"Consumes lots of resources" is an autoscaling problem** — solved by HPA and
   runtime tuning, not by routing around a language.

### B. Single runtime everywhere (all Java, or all Go) — DEFERRED

A full Java port re-runs a wave of already-green work before anything new ships; a
full Go commit contradicts the owner's stack lock. Contracts-first design keeps a
strangler re-implementation of any Go service in Java open as a later option —
without ever running two copies of the same capability.

## Consequences

- **Positive:** Wave 2 dispatches immediately (Java identity/wallet/FX/risk against
  the merged Go core); resource-critical path already runs on the lighter runtime;
  failover machinery (gateway weights, canary, HPA) is real from day one.
- **Negative / accepted costs:** two toolchains and CI paths (mitigated: shared
  compose, shared contracts repo, per-runtime Make targets); hiring/onboarding must
  cover both runtimes; cross-runtime integration must always go through contracts
  (enforced by CI contract-diff gates).
- **Escape hatch:** any Go service may be strangler-rebuilt in Java later, behind the
  same gateway weights, without ever running duplicates (see alternative B).
