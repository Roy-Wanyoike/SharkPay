# SharkPay Core — Data Model & Ledger DDL

| | |
|---|---|
| **Companion to** | [Architecture](ARCHITECTURE.md) · [State Machines](STATE-MACHINES.md) |
| **Status** | Approved baseline — schema changes require a decision-log entry |

---

## 1. Conventions

- PostgreSQL 16+. One schema per service; DDL below shows the **logical** model,
  physical tables live in the owning service's schema.
- All money: `currency CHAR(3)` + `amount_minor BIGINT` (integer minor units) +
  `exponent SMALLINT`. **No floats, no NUMERIC, ever.**
- All ids: `UUID v7` (time-ordered). All timestamps: `TIMESTAMPTZ`.
- Append-only tables revoke UPDATE/DELETE from the application role.
- Every mutable state change also writes a transition/audit row.

## 2. Chart of Accounts (ledger)

| Account type | Purpose | Examples |
|---|---|---|
| `wallet` | One per principal×currency balance container | `wallet:usr_123:KES` |
| `provider_clearing` | In-flight funds at a provider | `honeycoin:clearing:KES` |
| `fees` | Revenue recognition | `fees:payment:KES` |
| `fx_position` | FX conversion legs per currency pair | `fxpos:KES/USD:KES` |
| `suspense` | Ops-owned unresolved breaks | `suspense:recon:KES` |
| `settlement` | Provider settlement variance | `honeycoin:settlement:KES` |

## 3. Core DDL

### 3.1 Ledger (owned by `ledger`)

```sql
CREATE TABLE accounts (
    id            UUID PRIMARY KEY,            -- v7
    code          TEXT NOT NULL UNIQUE,        -- 'wallet:usr_123:KES'
    type          TEXT NOT NULL CHECK (type IN ('wallet','provider_clearing','fees',
                   'fx_position','suspense','settlement')),
    currency      CHAR(3) NOT NULL,
    owner_principal UUID,                       -- null for internal accounts
    status        TEXT NOT NULL DEFAULT 'active',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- One journal entry = one business transaction posting
CREATE TABLE journal_entries (
    id                UUID PRIMARY KEY,
    transaction_key   TEXT NOT NULL UNIQUE,     -- idempotency: 'payment:pay_xxx'
    source            TEXT NOT NULL,            -- payments|payouts|transfers|fx|fees|ops
    source_ref        UUID NOT NULL,
    entry_type        TEXT NOT NULL,            -- capture|hold|release|reversal|fee|fx|adjustment
    reverses_entry_id UUID REFERENCES journal_entries(id),
    reason            TEXT,
    operator_id       UUID,                     -- set for manual adjustments
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE postings (
    id          BIGSERIAL PRIMARY KEY,
    entry_id    UUID NOT NULL REFERENCES journal_entries(id),
    account_id  UUID NOT NULL REFERENCES accounts(id),
    debit       BIGINT NOT NULL DEFAULT 0 CHECK (debit  >= 0),
    credit      BIGINT NOT NULL DEFAULT 0 CHECK (credit >= 0),
    CHECK (debit = 0 OR credit = 0),
    CHECK (debit + credit > 0)
);
-- No UPDATE/DELETE grants; balance trigger asserts SUM(debits)=SUM(credits) per entry+currency
CREATE INDEX ON postings (account_id, id);
```

### 3.2 Identity (owned by `identity`)

```sql
CREATE TABLE principals (
    id            UUID PRIMARY KEY,
    shark_id      TEXT NOT NULL UNIQUE,          -- public handle 'SHARK-7F3K2M'
    type          TEXT NOT NULL CHECK (type IN ('individual','business','agent')),
    owner_principal UUID REFERENCES principals(id),   -- required for agents
    status        TEXT NOT NULL DEFAULT 'active',
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE kyc_records (
    id            UUID PRIMARY KEY,
    principal_id  UUID NOT NULL REFERENCES principals(id),
    tier          TEXT NOT NULL CHECK (tier IN ('unverified','limited','full')),
    method        TEXT NOT NULL,                 -- id_document|bureau|manual
    external_ref  TEXT,
    decided_by    UUID,                          -- null => auto
    decided_at    TIMESTAMPTZ,
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 3.3 Wallet (owned by `wallet`)

```sql
CREATE TABLE wallets (
    id            UUID PRIMARY KEY,
    principal_id  UUID NOT NULL,
    currency      CHAR(3) NOT NULL,
    status        TEXT NOT NULL DEFAULT 'active',   -- active|frozen|closed
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (principal_id, currency)
);
-- balances are projections from ledger.posting.committed.v1 + hold ledger:
CREATE TABLE holds (
    id            UUID PRIMARY KEY,
    wallet_id     UUID NOT NULL REFERENCES wallets(id),
    amount_minor  BIGINT NOT NULL CHECK (amount_minor > 0),
    source        TEXT NOT NULL,                -- payment|payout|transfer
    source_ref    UUID NOT NULL,
    state         TEXT NOT NULL DEFAULT 'active',  -- active|released|captured
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 3.4 Payments (owned by `payments`)

```sql
CREATE TABLE payment_intents (
    id                UUID PRIMARY KEY,
    client_key        TEXT NOT NULL,             -- caller idempotency key
    amount_minor      BIGINT NOT NULL CHECK (amount_minor > 0),
    currency          CHAR(3 NOT NULL,
    fee_minor         BIGINT NOT NULL DEFAULT 0,
    principal_id      UUID NOT NULL,
    destination_wallet UUID NOT NULL,
    rail_hint         TEXT,
    provider_id       UUID,                      -- set after routing
    provider_ref      TEXT,                      -- HoneyCoin tx id
    state             TEXT NOT NULL DEFAULT 'CREATED',
    expires_at        TIMESTAMPTZ NOT NULL,
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (principal_id, client_key)            -- idempotency scope
);

CREATE TABLE payment_state_transitions (
    id           BIGSERIAL PRIMARY KEY,
    intent_id    UUID NOT NULL REFERENCES payment_intents(id),
    from_state   TEXT NOT NULL,
    to_state     TEXT NOT NULL,
    trigger      TEXT NOT NULL,                  -- api|provider_callback|risk|expiry|ops
    actor        TEXT NOT NULL,                  -- principal|system|provider|operator
    note         TEXT,
    created_at   TIMESTAMPTZ NOT NULL DEFAULT now()
);
```

### 3.5 Payouts, FX, Providers, Risk, API platform (same pattern)

Payouts mirror payments (`payouts`, `payout_state_transitions` with destination
`rail + destination` instead of internal wallet). FX adds `quotes`
(`quote_id, base, quote, rate_minor_units, expires_at, state`) and `conversions`
(`entry_id` link). Providers add `providers`, `adapter_calls` (full request/response
audit, redacted), `provider_credentials` (vault reference only, never the secret).
Risk adds `rules`, `rule_evaluations`, `cases`, `limits`. API platform adds `api_keys`
(`hashed_key, scopes[], quota, principal_id, policy_id`), `webhook_endpoints`,
`webhook_deliveries` (attempt, status, next_retry_at), `request_logs`.

## 4. Money Invariants (enforced by triggers/constraints)

1. **Balance check** — sum of postings on a wallet account ≥ 0 at all times
   (deferred constraint on commit; holds make in-flight funds unavailable).
2. **Entry balance** — per journal entry, per currency: `Σdebit = Σcredit`.
3. **Idempotency** — `(source, transaction_key)` unique; duplicate posts return the
   original entry id.
4. **Reversal pairing** — a reversal entry must reference exactly one prior entry and
   post inverse legs to the same accounts.
5. **No mutation** — append-only grants; migrations that would rewrite financial rows
   are prohibited (add compensation entries instead).

## 5. Migrations

- Per-service `migrations/` with forward-only, numbered SQL files; applied by CI job;
  rollback = new forward migration. Ledger migrations additionally require ops sign-off
  (decision-log entry in this doc).
