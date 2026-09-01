# Services

One Go service per core domain. Each service owns exactly one PostgreSQL schema and
never touches another service's tables (see [docs/ARCHITECTURE.md](../docs/ARCHITECTURE.md)).

| Service | Domain | Phase | Status |
|---|---|---|---|
| `identity/` | D1 — Identity, SharkID, KYC | 1 | scaffold |
| `wallet/` | D2 — Wallets & holds | 2 | scaffold |
| `ledger/` | D3 — Double-entry ledger (source of truth) | 3 | scaffold |
| `providers/` | D7 — Provider gateway + HoneyCoin adapter | 4 | scaffold |
| `payments/` | D4 — Payment orchestration | 5 | scaffold |
| `payouts/` | D5 — Payouts & internal transfers | 6 | scaffold |
| `fx/` | D6 — FX & multi-currency | 7 | scaffold |
| `risk/` | D8 — Risk & compliance | 8 | scaffold |
| `api-gateway/` | D9 — Public API + webhooks | 9 | scaffold |
| `reconciliation/` | D10 — Ops & reconciliation | 10 | scaffold |

## Standard service layout

```
<service>/
  cmd/server/main.go     # entrypoint
  internal/              # handlers, domain logic, storage
  migrations/            # forward-only SQL for THIS service's schema only
  Dockerfile
  go.mod
```

Rules:
- Money logic uses `packages/go/money` — never raw integers in business code.
- External calls to providers only via `providers` service.
- Every state change publishes a versioned Kafka event registered in
  `contracts/events/`.
