# SharkPay Core — Product Requirements Document

| | |
|---|---|
| **Document status** | Approved baseline — source of truth |
| **Version** | 1.0 |
| **Applies to** | All SharkPay Core implementation work |
| **Related docs** | [Architecture](ARCHITECTURE.md) · [Data Model](DATA-MODEL.md) · [API Contracts](API-CONTRACTS.md) · [State Machines](STATE-MACHINES.md) · [Security](SECURITY.md) · [Roadmap](ROADMAP.md) |

---

## 1. Executive Summary

SharkPay is a **core financial platform** — the programmable money layer connecting
humans, businesses and AI agents. The platform exposes a double-entry, immutable ledger
as the single source of truth for money movement, wrapped in ten core domains: identity,
wallet, ledger, payments, payouts, FX, provider rails, risk & compliance, API platform,
and operations & reconciliation.

The critical product distinction: **SharkPay is not a wallet application.** Wallets,
consoles, merchant dashboards and third-party apps are *interfaces* that sit on top of
SharkPay Core. The core platform is the asset: the ledger, the orchestration engine, the
identity graph, the risk machinery and the public APIs. Money movement is executed over
pluggable payment rails (HoneyCoin at launch; M-Pesa, bank rails and blockchain rails
behind a uniform provider abstraction), so that no single rail failure or provider change
can compromise the platform.

The roadmap ships in four product releases — V1 Core (domestic payments foundation),
V2 Business (merchant, payout and escrow capabilities), V3 Web3 (stablecoin and on-chain
rails as first-class money), V4 Agentic Finance (AI agents as regulated financial actors
with policy-bound wallets).

## 2. Vision & Positioning

- **Vision:** every person, business and AI agent in our markets holds a SharkID and can
  move, hold and program money through one financial core.
- **Positioning:** infrastructure, not an app. We compete on trust, uptime, correctness
  of the ledger, and the depth of the API surface — not on UI novelty.
- **HoneyCoin relationship:** HoneyCoin is the launch money-movement provider behind the
  provider abstraction. The platform must never leak HoneyCoin-specific semantics into
  core domains; adapters translate, the core orchestrates.

## 3. Problem Statement

1. **Fragmentation:** consumers and SMEs juggle M-Pesa, bank apps, and crypto wallets
   with no unified identity, balance view, or statement of record.
2. **Unprogrammable money:** businesses cannot compose payments (split, escrow, sweep,
   schedule) without bespoke engineering per rail.
3. **Agents are unbanked:** AI agents increasingly transact on behalf of users but have
   no regulated, policy-bound financial identity or wallet.
4. **Reconciliation is manual:** existing rails provide weak idempotency and weak
   double-entry guarantees, making ops teams the "real" ledger.

## 4. Target Users & Personas

| Persona | Description | Primary needs |
|---|---|---|
| **P1 Consumer** | Individual holding multi-currency balances | Send/receive, request, statements, SharkID |
| **P2 Business / SME** | Merchant or company operating on SharkPay | Accept payments, payouts, sub-wallets, reconciliation exports |
| **P3 Developer** | Integrator building on the public APIs | Clean `/v1` REST, idempotency, webhooks, sandbox, SDKs |
| **P4 AI Agent (operator)** | An autonomous system acting for a user/business | Policy-bound wallet, spend limits, scoped API keys, audit trail |
| **P5 Operations** | Internal finance/ops staff | Reconciliation console, reversals via compensation entries, alerting |

## 5. Product Principles (non-negotiable)

1. **The ledger is the truth.** Every money movement is a balanced double-entry journal
   posting. Balances are derived views. Nothing financial is ever hard-deleted;
   corrections are compensation entries.
2. **Wallets are interfaces.** Any client — first-party or third-party — uses the same
   public APIs and the same permission model.
3. **Rails are pluggable.** Providers are adapters behind an internal interface; core
   domains never import provider SDKs directly.
4. **Idempotency everywhere.** Every state-changing public endpoint accepts an
   idempotency key; retries are safe by contract.
5. **Agents are first-class actors.** Agent wallets are not bolted on; identity, policy,
   and audit models treat agents as a principal type from day one.
6. **Fail closed on money, open on features.** Ambiguous states block funds movement and
   page a human; feature degradation never blocks the ledger.

## 6. System Context

Four layers (top to bottom):

1. **Interfaces** — Wallet app (React Native), Web Console (Next.js), Public APIs.
2. **SharkPay Core** — the ten domain services in this PRD.
3. **Provider abstraction** — internal gateway normalizing provider capabilities.
4. **Payment rails** — HoneyCoin, M-Pesa, bank rails, blockchain (EVM) rails.

## 7. Core Domains

### D1 — Identity
**Purpose:** one identity graph for humans, businesses and agents.
- SharkID: stable platform-wide identifier resolvable across interfaces.
- KYC/KYB onboarding tiers (unverified → limited → full) gating capability sets.
- Principal types: `individual`, `business`, `agent` (agents always have an owner).
- Login credentials, device registry, session management for first-party interfaces.
- **Out of scope (V1):** social login federation, delegation chains deeper than 1 level.

### D2 — Wallet
**Purpose:** multi-currency balance containers owned by principals.
- Wallet per principal per currency (`KES USD EUR GBP USDC USDT` at V1).
- Available / pending / held balance partitions (held reserved by in-flight payments).
- Deposit addresses per rail where applicable; virtual account mapping.
- **Out of scope (V1):** interest, yield, lending features.

### D3 — Ledger
**Purpose:** the immutable double-entry source of truth for all money.
- Journal entries: every posting has ≥ 2 legs, debits = credits, per currency.
- Chart of accounts: user wallet accounts, provider clearing accounts, fee accounts,
  FX position accounts, operational suspense accounts.
- Compensation entries for reversals/refunds — never mutation of posted entries.
- Idempotent posting API consumed only by internal domain services.
- **Out of scope (V1):** generalized arbitrary account charts for customers.

### D4 — Payments
**Purpose:** collect money into SharkPay wallets.
- Payment intents with amount, currency, rail hint, expiry, and state machine (see
  [State Machines](STATE-MACHINES.md)).
- Orchestration via Temporal workflows; provider selection by router policy
  (cost, availability, capability, latency).
- Fees computed at intent creation; fee schedule per rail/currency.
- Webhook emission at every terminal or intermediate state change.

### D5 — Payouts & Transfers
**Purpose:** move money out and between wallets.
- Transfers: wallet → wallet inside the core (single ledger transaction, no rail).
- Payouts: wallet → external destination via provider adapter (M-Pesa, bank, on-chain).
- Holders, scheduling, batching, retry policies with backoff.
- Return handling for reversed/returned payouts (compensation entry + state).

### D6 — FX & Multi-Currency
**Purpose:** convert value between supported currencies.
- Quote → lock → convert lifecycle with TTL'd quotes.
- Rate sources with mark-up policy; FX position accounts in the ledger.
- Cross-currency payment composition (collect KES → settle USD) in V2.

### D7 — Provider / Rails
**Purpose:** uniform abstraction over external money movers.
- Internal `Provider` interface: capabilities, quote, initiate, poll/webhook, cancel,
  reverse, reconcile-report.
- **HoneyCoin adapter is the launch implementation** — reference adapter all future
  adapters copy.
- Provider health, circuit breaking, credential vaulting per provider.
- Router: policy engine scoring candidate providers per payment.

### D8 — Risk & Compliance
**Purpose:** keep the platform safe and compliant.
- Transaction monitoring rules (velocity, limits, geo, counterparty, pattern).
- KYC/AML checks wired into state transitions of payments/payouts.
- Case management for ops review; SAR-ready reporting hooks.
- Velocity limits per tier, per currency, per principal type (agents stricter).

### D9 — API Platform
**Purpose:** the public programmable surface.
- API keys with scopes, per-key quotas; HMAC-signed webhooks with replay protection.
- Versioned REST `/v1` (OpenAPI 3.1 in `contracts/`); idempotency keys mandatory.
- Sandbox environment with simulated providers.
- Usage analytics surfaced in Console.

### D10 — Operations & Reconciliation
**Purpose:** guarantee ledger ↔ provider statement agreement.
- Daily reconciliation per provider; break detection with aging.
- Ops console: search, case handling, manual compensation entry issuance with 4-eyes
  approval.
- Settlement reporting; fee recognition reports.

## 8. Expansion Domains (post-V1)

| # | Domain | Release |
|---|---|---|
| X1 | Merchant (checkout, sub-merchants, split payments) | V2 |
| X2 | Escrow (milestone & conditional release) | V2 |
| X3 | Marketplace (split, hold, disbursement at scale) | V2 |
| X4 | Cards (issuing, virtual cards) | V2/V3 |
| X5 | Treasury (sweeping, pooling, inter-wallet ops for corporates) | V2/V3 |
| X6 | Web3 (on-chain rails, self-custody bridges) | V3 |
| X7 | Agent Wallets (policy engine, delegated spending) | V4 |
| X8 | Billing & Subscriptions (recurring intents) | V3 |
| X9 | Rewards (loyalty hooks) | V3/V4 |
| X10 | Lending hooks (credit decisioning interfaces) | V4 |

## 9. Functional Requirements (selection — full matrix per domain)

| ID | Requirement | Domain |
|---|---|---|
| FR-101 | Create principal with SharkID; dedupe by phone/email/national ID | D1 |
| FR-201 | Open multi-currency wallets; freeze/unfreeze per compliance action | D2 |
| FR-301 | Post immutable double-entry journal entries; reject unbalanced | D3 |
| FR-302 | Reverse via compensation entry only; capture reason + operator | D3 |
| FR-401 | Create payment intent with idempotency key; safe retry returns original | D4 |
| FR-402 | Route payment via provider router; failover within SLA | D4/D7 |
| FR-501 | Transfer between internal wallets settles in ≤ 1 ledger transaction | D5 |
| FR-502 | Payout to M-Pesa/bank/on-chain with status webhooks | D5/D7 |
| FR-601 | FX quote with TTL; conversion posts balanced FX legs | D6 |
| FR-701 | Provider adapter interface: quote/initiate/poll/cancel/reverse/report | D7 |
| FR-702 | HoneyCoin adapter passes full conformance suite | D7 |
| FR-801 | Risk rules evaluated pre-authorization and post-completion | D8 |
| FR-901 | Scoped API keys; webhook HMAC signatures + replay window | D9 |
| FR-902 | Sandbox simulates all provider states incl. failures/returns | D9 |
| FR-1001 | Daily reconciliation report per provider; break aging alerts | D10 |

## 10. Non-Functional Requirements

| ID | Requirement | Target |
|---|---|---|
| NFR-01 | Payment intent → provider hand-off (p99) | ≤ 2 s |
| NFR-02 | Internal transfer ledger commit (p99) | ≤ 500 ms |
| NFR-03 | API availability (monthly) | 99.95 % |
| NFR-04 | Ledger durability: zero tolerated entry loss | RPO = 0, RTO ≤ 15 min |
| NFR-05 | Horizontal scale: 1,000 TPS payments sustained | Load-tested |
| NFR-06 | All financial writes audited (who/what/when/why) | 100 % |
| NFR-07 | Data residency options for regulated markets | V2+ |

## 11. Money Invariants (enforced at ledger level)

1. `SUM(debits) = SUM(credits)` for every journal entry, per currency.
2. No wallet balance may go negative in `available` partition.
3. Every ledger posting references exactly one business transaction
   (payment/payout/transfer/fx/fee/adjustment).
4. Reversals always create new entries; original entries are immutable.
5. Every state change of money is idempotent under a unique transaction key.

## 12. Release Plan (summary — details in Roadmap)

| Release | Theme | Headline capabilities |
|---|---|---|
| **V1 Core** | Trust the core | Identity, multi-currency wallets, double-entry ledger, payments via HoneyCoin, internal transfers, risk tier-1, public API v1, reconciliation |
| **V2 Business** | Money at work | Merchant checkout, escrow, marketplace splits, payout expansion, FX cross-border, treasury basics |
| **V3 Web3** | Money on-chain | Stablecoin rails (USDC/USDT) as native money, self-custody bridge, on-chain payouts |
| **V4 Agentic** | Money that acts | Agent wallets, policy engine, delegated limits, agent-native API & audit surfaces |

## 13. Success Metrics

- Ledger integrity: 0 unexplained breaks in daily reconciliation (target: 0, always).
- API adoption: active API keys, webhook delivery success ≥ 99.9 %.
- Payment success rate (excluding user-abandon): ≥ 97 % within 30 days of provider
  incident-free operation.
- Time-to-first-payment for a sandbox developer: < 15 minutes.

## 14. Risks & Mitigations

| Risk | Mitigation |
|---|---|
| Provider single-point (HoneyCoin at launch) | Provider abstraction from day 1; adapter conformance suite; router ready for second rail |
| Regulatory (KYC/AML, agent activity) | Tiered KYC gates capabilities; risk domain wired into state machines; policy-bound agents |
| Ledger integrity under incident | Immutable ledger + compensation model; 4-eyes on manual adjustments; reconciliation SLO |
| Complexity creep across 10 domains | Strict service boundaries & event contracts; shared `packages/` types |

## 15. Decision Log

| Decision | Rationale |
|---|---|
| PostgreSQL as ledger store | ACID + row-level locking; proven for double-entry at this scale |
| Temporal for orchestration | Durable, replayable workflows; matches payment lifecycle semantics |
| Kafka for domain events | Decouples domains; replayable reconciliation feeds |
| HoneyCoin as launch provider only | Keeps core rail-agnostic; adapters translate, core orchestrates |
| REST `/v1` (not gRPC public) | Developer familiarity; gRPC internal only |
