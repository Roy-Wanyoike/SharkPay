# SharkPay Core — System Architecture

| | |
|---|---|
| **Companion to** | [PRD](PRD.md) · [Data Model](DATA-MODEL.md) · [API Contracts](API-CONTRACTS.md) |
| **Status** | Approved baseline — source of truth for service boundaries |

---

## 1. Architectural Style

Microservices in Go, each owning its PostgreSQL schema (**database-per-service**),
communicating:

- **Synchronously** — internal gRPC with mTLS for request/response (balance checks,
  identity lookups, provider calls).
- **Asynchronously** — Kafka domain events for state propagation, webhook fan-out, and
  reconciliation feeds.
- **Workflow orchestration** — Temporal for every multi-step money movement (payments,
  payouts, FX, onboarding). Temporal workflows are the *only* place retry/failover logic
  lives; services themselves stay simple and idempotent.

Hard rules:

1. A service may read/write **only its own schema**. Cross-domain reads go through the
   owning service's API or a published event projection.
2. Only `ledger` writes `journal_entries`. Every other service requests postings via the
   ledger's internal idempotent API.
3. Only `providers` talks to external rails. No domain service imports a provider SDK.
4. Every Kafka event is versioned (`payments.payment.completed.v1`) and registered in
   `contracts/events/`.

## 2. Service Map & Ownership

| Service | Owns (tables) | Exposes (internal gRPC) | Publishes (Kafka topics) | Consumes |
|---|---|---|---|---|
| `identity` | principals, shark_ids, kyc_records, devices, sessions | `GetPrincipal`, `VerifyKyc`, `CreateAgent` | `identity.principal.created.v1`, `identity.kyc.tier.changed.v1` | — |
| `wallet` | wallets, balance_snapshots, deposit_addresses | `GetWallet`, `Hold/Release funds`, `FreezeWallet` | `wallet.balance.changed.v1` | `payments.*`, `payouts.*` |
| `ledger` | accounts, journal_entries, postings, transaction_keys | `PostTransaction`, `ReverseTransaction`, `GetStatement` | `ledger.posting.committed.v1` | — (pull-only) |
| `payments` | payment_intents, payment_state_transitions, fees | `CreateIntent`, `GetIntent`, `CancelIntent` | `payments.payment.*.v1` | `risk.decision.*`, `providers.*` |
| `payouts` | payouts, payout_state_transitions, batches | `CreatePayout`, `SchedulePayout` | `payouts.payout.*.v1` | `risk.decision.*`, `providers.*` |
| `fx` | quotes, conversions, rate_sources | `GetQuote`, `LockQuote`, `Convert` | `fx.conversion.executed.v1` | `payments.*` (cross-currency V2) |
| `providers` | providers, provider_credentials (vault ref), adapter_calls, health | `Quote`, `Initiate`, `Cancel`, `Reverse`, `Poll` | `providers.transfer.*.v1` | — |
| `risk` | rules, rule_evaluations, cases, limits | `EvaluatePre`, `EvaluatePost`, `OpenCase` | `risk.decision.v1`, `risk.case.opened.v1` | all money-movement events |
| `api-gateway` | api_keys, quotas, webhook_endpoints, webhook_deliveries, request_logs | public REST `/v1` + webhook dispatcher | `platform.webhook.*.v1` | all domain events (for webhook fan-out) |
| `reconciliation` | recon_runs, breaks, provider_statements, settlement_reports | `RunRecon`, `GetBreaks` | `recon.break.detected.v1` | `ledger.*`, `providers.*` |

## 3. Payment Flow (sequence)

```
Client        api-gateway    payments       risk      wallet     providers     ledger
  │ POST /v1/payments (Idempotency-Key) │
  ├──────────────►│  validate + scope ──► │
  │               │ ┌── Temporal: CreatePaymentWorkflow ──────────────┐
  │               │ │ payments.CreateIntent ──► risk.EvaluatePre ──► │
  │               │ │   hold funds (wallet.Hold) ────────────────────│
  │               │ │   providers.Quote → router selects HoneyCoin   │
  │               │ │   providers.Initiate ──► adapter → rail        │
  │               │ │   poll/webhook confirm ──► risk.EvaluatePost   │
  │               │ │   release hold → capture → ledger.PostTransaction
  │               │ └── emit payments.payment.completed.v1 ──────────┘
  │◄── 201 {payment: PROCESSING} │
  │               │           ... webhook delivery (api-gateway, HMAC) to merchant
```

Key invariants of the flow: funds are **held before** provider initiation; the ledger
posting happens **on confirmation**, never on intent creation; every step writes a state
transition row (`payment_state_transitions`) making the intent fully replayable.

## 4. Provider Router & HoneyCoin Adapter

### 4.1 Router (inside `payments`/`payouts` orchestration)

Input: amount, currency, destination type, principal tier, latency preference.
Scoring: `score = w1·cost + w2·p99_latency + w3·health + w4·capability_fit`
(with hard filters first: supports currency? supports rail? not circuit-open?).

### 4.2 Provider interface (Go, internal)

```go
type Provider interface {
    Name() string
    Capabilities() Capabilities            // currencies, rails, reversals, on-chain
    Quote(ctx context.Context, r QuoteRequest) (Quote, error)
    Initiate(ctx context.Context, r InitiateRequest) (ProviderRef, error)
    Poll(ctx context.Context, ref ProviderRef) (TransferStatus, error)
    HandleCallback(ctx context.Context, cb Callback) (TransferStatus, error)
    Cancel(ctx context.Context, ref ProviderRef) error
    Reverse(ctx context.Context, ref ProviderRef) (ProviderRef, error)
    ReconcileReport(ctx context.Context, window Window) ([]ProviderLine, error)
}
```

### 4.3 HoneyCoin adapter (launch reference)

- Implements every method; maps HoneyCoin status codes → internal `TransferStatus`.
- All calls: signed, idempotent (adapter-level key = our transaction key), timed out
  with circuit breaker (5 failures/30 s → open 60 s → half-open probe).
- Secrets from vault (AWS Secrets Manager); never in env of other services.
- Callbacks verified (signature + timestamp window + replay cache in Redis).
- **Conformance suite** (`tests/providers/`): a provider implementation is production-
  eligible only after passing quote/initiate/poll/cancel/reverse/report + failure
  injection + callback forgery tests.

## 5. Ledger Architecture

- **Append-only.** `journal_entries` and `postings` have no UPDATE/DELETE grants for app
  roles; the only "correction" is a new compensating entry linked via `reverses_entry_id`.
- **Balanced per currency.** A posting transaction that is unbalanced for any currency is
  rejected by a deferred constraint trigger.
- **Serialization.** Wallet account postings take a row lock on the account
  (`SELECT ... FOR UPDATE` on `accounts`) ordered by account id to prevent deadlocks.
- **Projections.** `wallet` balances and console statements are event-sourced projections
  from `ledger.posting.committed.v1`; the ledger remains the sole authority.

## 6. Multi-Currency & FX

- Money is stored as `(currency, minor_units INTEGER, exponent)` — never floats.
- FX: `fx` maintains rate sources (HoneyCoin indicative + backup source), applies
  mark-up policy, issues TTL'd quotes; conversion = 1 journal entry with 4 legs
  (debit source wallet, credit FX position-CCY1, debit FX position-CCY2, credit target
  wallet) so FX P&L is observable in the ledger.

## 7. Identity & Agent Model

- Principal types: `individual | business | agent`. An agent principal always references
  an owner principal and a policy document (scopes, limits, velocity, allowed rails).
- API keys issued to agents are bound to the policy; enforcement happens at
  `api-gateway` (scopes/quota) **and** at `payments/payouts` (per-transaction policy
  evaluation pre-initiation).

## 8. Deployment Topology

- **AWS, EKS (Kubernetes), Terraform** in `infrastructure/` (per-env workspaces:
  `sandbox`, `staging`, `prod`).
- PostgreSQL: primary + sync replica (RPO=0), PITR to any second in 15-min window.
- Kafka: MSK, 3 brokers, min.insync.replicas=2 for money topics.
- Redis: ElastiCache — idempotency replay cache, locks, rate counters only
  (**never** authoritative state).
- Temporal: dedicated namespace per env; workflows versioned with sticky-safe code.
- Observability: OpenTelemetry traces (W3C traceparent propagated through Kafka
  headers), Prometheus metrics, Grafana dashboards + alerts per SLO; JSON structured
  logs with `trace_id`, `principal_id`, `transaction_id` on every line.

## 9. Environments

| Env | Purpose | Providers | Data |
|---|---|---|---|
| `sandbox` | Developer testing | Simulated provider (all states scriptable) | synthetic |
| `staging` | Pre-prod, conformance | HoneyCoin test credentials | synthetic |
| `prod` | Live | HoneyCoin live | real, PCI-zone policies apply |

## 10. Repository Structure (enforcement)

- `services/<name>/` — Go module per service: `cmd/`, `internal/`, `migrations/` (owned
  schema only), `Dockerfile`.
- `packages/` — shared Go + TypeScript libs (`packages/go/money`, `packages/ts/sdk`,
  `packages/events` — generated event types).
- `contracts/` — OpenAPI 3.1 (`contracts/openapi/v1/*.yaml`) and event schemas; CI
  validates services against contracts.
- CI (GitHub Actions): lint → unit → contract tests → integration (docker-compose:
  Postgres, Kafka, Temporal, wiremock providers) → build → scan → deploy gate.
