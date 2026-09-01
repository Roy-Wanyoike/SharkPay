# ADR 001: Lock the platform technology stack

- **Status:** Accepted
- **Date:** 2026-09-01
- **Deciders:** @Roy-Wanyoike

## Context

SharkPay's foundation is merged (PR #1: Go money library, double-entry ledger, provider
gateway + HoneyCoin adapter; OpenAPI 3.1 + CloudEvents contracts; docker-compose dev
stack; CI). Before dispatching Wave 2 (identity, wallet, FX, risk), the platform stack
must be locked so agents build against one target instead of guessing.

The stack below was selected by the owner on 2026-09-01. This ADR records it in-repo
(so it stops living in chat scrollback) and pins the implementation notes that keep it
internally consistent with what is already merged.

## Decision

| Layer | Locked choice |
|---|---|
| **Frontend** | Next.js 16, React 19, TypeScript, Tailwind, shadcn/ui, Radix, TanStack Query, React Hook Form, Zod |
| **Mobile** | Expo, React Native, TypeScript, SQLite, offline mutation queue |
| **Backend (platform services)** | Java 25 LTS, Spring Boot, Spring Security, Hibernate/JPA, Flyway, REST / OpenAPI 3.1 |
| **Backend (core money path)** | Go — ledger (WP-3), provider gateway (WP-4), `packages/go/money`. See ADR 002 |
| **Data** | PostgreSQL 18, PostGIS 3.6 (only when geo features enter scope), Redis (cache/idempotency/replay only — never ledger truth), R2/S3 |
| **Distributed systems** | Temporal (Java SDK for platform services), NATS JetStream (CloudEvents transport) |
| **Identity** | Keycloak (authn, sessions, SSO). Custom domain logic stays in the identity service: KYC tiers, SharkID, devices |
| **AI** | Provider-agnostic AI gateway (vision, speech-to-text, LLM, embeddings), Temporal AI workflows, pgvector on existing PG for RAG |
| **Infrastructure** | Docker, Kubernetes, Helm, Terraform/OpenTofu, Argo CD (+ Argo Rollouts for canary), GitHub Actions |
| **Observability** | OpenTelemetry, Prometheus, Grafana, Loki, Tempo (+ Alertmanager — see below) |
| **Testing** | JUnit, Testcontainers, REST Assured, Vitest, React Testing Library, Playwright (web), Maestro/Detox (mobile) |
| **Search / analytics — later** | OpenSearch, ClickHouse (post-V1, only on demonstrated need) |

## Implementation notes (corrections applied at build time)

1. **Spring Boot line:** use the **4.x line (Framework 7 / Jakarta 11)** for all new
   services. Do not start new services on the 3.x line — it would force a migration
   within months of launch.
2. **Kubernetes:** manifests target **1.36** initially (managed control planes EKS/GKE
   trail the freshly released 1.37 by 1–2 minors); bump policy is "latest minus one".
3. **Broker:** locked = **NATS JetStream**. The dev compose currently ships Redpanda
   (Kafka API). Wave 2 swaps the compose stack to NATS. CloudEvents contracts are
   transport-agnostic, so this is a dev-infra swap, not a contract change.
4. **Money representation (hard rule):** Java uses `long` minor units (or
   `BigDecimal`/JSR-354 at display edges); TypeScript uses minor units + `bigint`;
   Go uses the merged `packages/go/money`. **Floating-point money is forbidden** and
   enforced by lint / ArchUnit rules in CI.
5. **Resilience:** every Spring service includes **Resilience4j** (circuit breaker per
   provider, bulkhead, timeout, retry-with-jitter). The Go gateway already implements
   breaker semantics (5 failures / 30s window → open 60s).
6. **PostGIS 3.6** stays out of the base PG images until agent/merchant geo-mapping
   becomes a real V1 feature — keeps images lean and upgrades simple.
7. **Alertmanager is part of the observability stack** (Prometheus without alerting is
   a dashboard, not monitoring). SLOs: 99.9% API availability, p99 < 500ms, payment
   success rate, webhook delivery SLA — with error budgets and paging.

## Consequences

- Agents can be dispatched against a single, unambiguous target stack.
- Polyglot split (Go core + Java platform) is governed by ADR 002.
- Changing any row above requires a new ADR superseding this one.
