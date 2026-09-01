# SharkPay Core — Roadmap & Engineering Phases

| | |
|---|---|
| **Companion to** | [PRD](PRD.md) · [Architecture](ARCHITECTURE.md) |
| **Rule** | A phase is done when its **exit criteria** pass, not when code exists. |

---

## Product releases

| Release | Theme | Scope |
|---|---|---|
| V1 Core | Trust the core | Identity, wallets, double-entry ledger, HoneyCoin payments, transfers, risk tier-1, public API + webhooks, reconciliation, sandbox |
| V2 Business | Money at work | Merchant, escrow, marketplace, payout expansion, cross-border FX, treasury basics |
| V3 Web3 | Money on-chain | Stablecoin native rails, self-custody bridge, on-chain payouts, billing/subscriptions |
| V4 Agentic | Money that acts | Agent wallets, policy engine, delegated limits, agent API/audit surfaces, lending hooks |

## Engineering phases (Phase 0–10)

| Phase | Name | Delivers | Exit criteria |
|---|---|---|---|
| **0** | Foundation | Monorepo CI, docker-compose dev stack (Postgres, Kafka, Temporal, wiremock), shared `packages/go/money`, OpenAPI skeleton, service skeletons | CI green on all skeletons; `money` lib property tests pass |
| **1** | Identity & principals | `identity` service: principals, SharkID, KYC tiers, sessions, events | Contract tests pass; KYC tier gates enforced in gateway stub |
| **2** | Wallet & holds | `wallet` service: wallets, deposit addresses, hold/release API, balance projection from ledger events | Hold/release idempotent; balance projection consistent under concurrent events |
| **3** | Ledger core | `ledger` service: accounts, journal entries, postings, transaction keys, compensation entries, statement API | Invariant trigger suite green (balance, entry-balance, reversal pairing); 1k TPS posting benchmark |
| **4** | Provider gateway & HoneyCoin | `providers` service, Provider interface, HoneyCoin adapter, circuit breaker, callback verify, conformance suite | Conformance suite (incl. failure injection + forgery) green |
| **5** | Payments orchestration | `payments` service: intents, Temporal workflow, router, state machine, fees | Full payment lifecycle in sandbox incl. expiry, reversal, failover |
| **6** | Transfers & payouts | `payouts` + internal transfers; return handling | Transfer atomicity tests; payout E2E to simulated rails |
| **7** | FX & multi-currency | `fx` service: quotes, lock, convert, 4-leg entries | FX conversion atomic; position accounts reconcile |
| **8** | Risk & compliance | `risk` service: rules engine, pre/post evaluation, cases, limits | Risk deny blocks at correct states; case flow E2E |
| **9** | API platform & webhooks | `api-gateway` public `/v1`, keys/scopes/quotas, webhook dispatcher + Console usage views | OpenAPI-validated; HMAC + replay tests; sandbox live |
| **10** | Ops, recon & hardening | `reconciliation` service, recon console, 4-eyes adjustments, load tests, SLO dashboards, launch checklist | Zero-break recon on 7-day simulated traffic; 1k TPS load test; go-live review |

## Dependency order (critical path)

```
Phase 0 → 1 → 2 → 3 → 4 → 5 → 6
                  3 → 7 (parallel with 4–6)
                  8 after 5/6 (needs events), 9 after 5 (needs webhooks), 10 last
```

## Definition of Done (all phases)

- Unit + contract + integration tests green in CI.
- OpenAPI/event schemas updated in `contracts/` (if surface changed).
- Observability: metrics, logs with trace_id, alerts wired.
- Docs updated: state machine tables here and in [State Machines](STATE-MACHINES.md).
- A decision-log entry in the PRD for any spec deviation.
