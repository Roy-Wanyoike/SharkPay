# SharkPay

> **The programmable financial layer for humans, businesses and AI agents.**
> The infrastructure connecting money, applications and agents. Financial infrastructure in motion.

SharkPay is **not a wallet app**. It is a **core financial platform** — a double-entry money
engine with identity, payments, FX, payouts, risk and an API platform at its center.
Wallets, consoles and third-party apps are just *interfaces* on top of the core.

```
┌─────────────────────────────────────────────────────────────┐
│  Interfaces        Wallet app · Web Console · Public APIs   │
├─────────────────────────────────────────────────────────────┤
│  SharkPay Core     Identity · Wallet · Ledger · Payments    │
│  (the platform)    Payouts · FX · Risk · API Platform       │
├─────────────────────────────────────────────────────────────┤
│  Payment Rails     HoneyCoin · M-Pesa · Banks · Blockchain  │
│                    (pluggable, replaceable providers)       │
└─────────────────────────────────────────────────────────────┘
```

## Status

**Foundation / specification stage.** The product & engineering specification is complete
(see [`docs/`](docs/)). Implementation work has not started — services, apps and
infrastructure directories are scaffolding only. See the
[engineering roadmap](docs/ROADMAP.md) for build order.

## Documentation (source of truth)

| Document | Contents |
|---|---|
| [`docs/PRD.md`](docs/PRD.md) | Product requirements — vision, personas, 10 core domains, 10 expansion domains, functional & non-functional requirements, V1–V4 release plan |
| [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md) | System architecture, service boundaries, event topology, provider router & HoneyCoin adapter, deployment topology |
| [`docs/DATA-MODEL.md`](docs/DATA-MODEL.md) | Double-entry ledger model, PostgreSQL DDL, ownership rules, money invariants |
| [`docs/API-CONTRACTS.md`](docs/API-CONTRACTS.md) | Public API contracts — `/v1` surface, idempotency, error model, webhook event catalog |
| [`docs/STATE-MACHINES.md`](docs/STATE-MACHINES.md) | Payment, payout, transfer, KYC and wallet state machines with invariants |
| [`docs/SECURITY.md`](docs/SECURITY.md) | Security model — authn/authz, agent policy engine, secrets, AML/KYC controls |
| [`docs/ROADMAP.md`](docs/ROADMAP.md) | V1 Core → V4 Agentic Finance product phases and Phase 0–10 engineering plan |

## Monorepo layout

```
apps/             End-user interfaces
  web/              Next.js + TypeScript Console & developer dashboard
  mobile/           React Native wallet app
services/         Go microservices (one per core domain) — see services/README.md
packages/         Shared libraries (types, SDKs, clients)
contracts/        API contracts (OpenAPI 3.1) & event schemas
infrastructure/   Terraform, Kubernetes manifests, CI/CD
docs/             Product & engineering specifications (this directory)
tests/            Cross-service integration & load tests
```

## Core domains

| # | Domain | Service |
|---|---|---|
| 1 | Identity (SharkID, KYC) | `services/identity` |
| 2 | Wallet (multi-currency balances) | `services/wallet` |
| 3 | Ledger (double-entry source of truth) | `services/ledger` |
| 4 | Payments (orchestration, provider router) | `services/payments` |
| 5 | Payouts & Transfers | `services/payouts` |
| 6 | FX & Multi-Currency | `services/fx` |
| 7 | Provider / Rails gateway (HoneyCoin adapter) | `services/providers` |
| 8 | Risk & Compliance | `services/risk` |
| 9 | API Platform (keys, webhooks, quotas) | `services/api-gateway` |
| 10 | Operations & Reconciliation | `services/reconciliation` |

## Tech stack

Go (services) · PostgreSQL (ledger & state) · Temporal (workflows) · Kafka (events) ·
Redis (cache/locks) · Next.js + TypeScript (web) · React Native (mobile) ·
AWS + Kubernetes + Terraform (infra) · EVM / Solidity / Foundry / viem (web3 rails) ·
OpenTelemetry / Prometheus / Grafana (observability)

## Supported currencies (V1)

`KES` `USD` `EUR` `GBP` `USDC` `USDT`

## Contributing

All implementation work must trace back to a requirement in [`docs/PRD.md`](docs/PRD.md)
and respect the boundaries defined in [`docs/ARCHITECTURE.md`](docs/ARCHITECTURE.md).
The ledger is immutable — design decisions never assume deletion of financial rows.
