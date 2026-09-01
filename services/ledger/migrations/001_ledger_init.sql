-- 001_ledger_init.sql — SharkPay ledger schema (docs/DATA-MODEL.md §3.1, §4)
--
-- Invariants enforced HERE (the SQL backstop behind the domain/service
-- checks — belt & suspenders, per ARCHITECTURE.md §5):
--   #1 per-entry, per-currency: SUM(debits) = SUM(credits)
--   #2 wallet accounts: running balance (credits − debits) ≥ 0
--   #3 idempotency: (source, transaction_key) unique
--   #4 double reversal: at most ONE reversal per reversed entry
--   #5 append-only: app role has no UPDATE/DELETE on journal_entries/postings
--
-- Money is ALWAYS integer minor units. Floats are forbidden.

BEGIN;

-- ---------------------------------------------------------------------------
-- accounts (the chart of accounts)
-- ---------------------------------------------------------------------------

CREATE TABLE accounts (
    id              UUID PRIMARY KEY,
    code            TEXT NOT NULL UNIQUE,
    type            TEXT NOT NULL CHECK (type IN
                        ('wallet', 'provider_clearing', 'fees',
                         'fx_position', 'suspense', 'settlement')),
    currency        CHAR(3) NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    owner_principal UUID,                    -- null for internal accounts
    status          TEXT NOT NULL DEFAULT 'active'
                        CHECK (status IN ('active', 'frozen', 'closed')),
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX accounts_owner_idx ON accounts (owner_principal) WHERE owner_principal IS NOT NULL;
CREATE INDEX accounts_type_currency_idx ON accounts (type, currency);

-- ---------------------------------------------------------------------------
-- journal entries (one per business transaction posting)
-- ---------------------------------------------------------------------------

CREATE TABLE journal_entries (
    id                UUID PRIMARY KEY,
    transaction_key   TEXT NOT NULL,
    source            TEXT NOT NULL CHECK (source IN
                          ('payments', 'payouts', 'transfers', 'fx', 'fees', 'ops')),
    source_ref        UUID NOT NULL,
    entry_type        TEXT NOT NULL CHECK (entry_type IN
                          ('hold', 'release', 'capture', 'reversal',
                           'fee', 'fx', 'adjustment')),
    reverses_entry_id UUID REFERENCES journal_entries (id),
    reason            TEXT CHECK (char_length(reason) <= 500),
    operator_id       UUID,                  -- set for manual (ops) adjustments
    created_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    -- Invariant #3: idempotency scoped to (source, transaction_key)
    UNIQUE (source, transaction_key),
    -- Invariant #4: an entry may be reversed at most once (partial unique:
    -- NULLs are distinct, non-reversal rows are unconstrained)
    UNIQUE (reverses_entry_id) DEFERRABLE INITIALLY DEFERRED
);

CREATE INDEX journal_entries_source_ref_idx ON journal_entries (source_ref);
CREATE INDEX journal_entries_reverses_idx
    ON journal_entries (reverses_entry_id) WHERE reverses_entry_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- postings (the legs; append-only, no UPDATE/DELETE for the app role)
-- ---------------------------------------------------------------------------

CREATE TABLE postings (
    id          BIGSERIAL PRIMARY KEY,
    entry_id    UUID NOT NULL REFERENCES journal_entries (id),
    account_id  UUID NOT NULL REFERENCES accounts (id),
    debit       BIGINT NOT NULL DEFAULT 0 CHECK (debit  >= 0),
    credit      BIGINT NOT NULL DEFAULT 0 CHECK (credit >= 0),
    CHECK (debit = 0 OR credit = 0),   -- exactly one side nonzero
    CHECK (debit + credit > 0)         -- and it is positive
    -- NOTE: MaxLegMinorUnits (1e15) is enforced in the domain layer; the SQL
    -- bound would be redundant with the trigger math below but is kept in
    -- the domain for clearer error messages.
);

CREATE INDEX postings_account_id_idx ON postings (account_id, id);
CREATE INDEX postings_entry_id_idx ON postings (entry_id);

-- ---------------------------------------------------------------------------
-- Invariant #1: per entry, per currency, debits = credits.
-- Deferred constraint trigger: fires at COMMIT, after the whole entry (all
-- legs) has been inserted, so multi-leg and multi-currency entries are
-- validated as complete units — never partially.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION ledger_assert_entry_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    unbalanced_count INT;
BEGIN
    SELECT count(*) INTO unbalanced_count
    FROM (
        SELECT p.entry_id, a.currency
        FROM postings p
        JOIN accounts a ON a.id = p.account_id
        WHERE p.entry_id = NEW.entry_id
        GROUP BY p.entry_id, a.currency
        HAVING SUM(p.debit) <> SUM(p.credit)
    ) unbalanced;
    IF unbalanced_count > 0 THEN
        RAISE EXCEPTION
            'unbalanced journal entry %: debits <> credits per currency',
            NEW.entry_id
            USING ERRCODE = 'check_violation';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_entry_balanced
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION ledger_assert_entry_balanced();

-- ---------------------------------------------------------------------------
-- Invariant #2: wallet accounts never go negative.
-- Wallet balance = SUM(credits) − SUM(debits) over ALL postings on the
-- account. Internal accounts (clearing/suspense/fees/…) may go negative —
-- they absorb in-flight and break states; wallet overdrafts are impossible.
-- ---------------------------------------------------------------------------

CREATE OR REPLACE FUNCTION ledger_assert_wallet_non_negative() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    acct_type TEXT;
    balance   BIGINT;
BEGIN
    SELECT type INTO acct_type FROM accounts WHERE id = NEW.account_id;
    IF acct_type = 'wallet' THEN
        SELECT COALESCE(SUM(credit), 0) - COALESCE(SUM(debit), 0)
            INTO balance
        FROM postings WHERE account_id = NEW.account_id;
        IF balance < 0 THEN
            RAISE EXCEPTION
                'wallet account % would go negative (balance %)',
                NEW.account_id, balance
                USING ERRCODE = 'check_violation';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_wallet_non_negative
    AFTER INSERT ON postings
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW
    EXECUTE FUNCTION ledger_assert_wallet_non_negative();

-- ---------------------------------------------------------------------------
-- Invariant #5: append-only ledger. The application role can INSERT and
-- SELECT but never UPDATE or DELETE financial rows. Corrections are new
-- (compensating) entries only.
-- ---------------------------------------------------------------------------

DO $$
BEGIN
    IF NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'sharkpay_app') THEN
        CREATE ROLE sharkpay_app LOGIN;
    END IF;
END
$$;

GRANT SELECT, INSERT, UPDATE ON accounts TO sharkpay_app;  -- status transitions only
GRANT SELECT, INSERT ON journal_entries TO sharkpay_app;
GRANT SELECT, INSERT ON postings TO sharkpay_app;
GRANT USAGE, SELECT ON SEQUENCE postings_id_seq TO sharkpay_app;

REVOKE UPDATE, DELETE ON journal_entries FROM sharkpay_app;
REVOKE UPDATE, DELETE ON postings FROM sharkpay_app;

COMMENT ON ROLE sharkpay_app IS
    'SharkPay ledger application role: append-only on journal_entries/postings (DATA-MODEL §4 invariant #5). Password is managed by the deploy secret store, not by this migration.';

COMMENT ON TABLE journal_entries IS
    'Immutable double-entry journal. Reversals reference the compensated entry via reverses_entry_id and post exact inverse legs.';

COMMENT ON TABLE postings IS
    'Journal legs. debit XOR credit nonzero; balances enforced by trg_entry_balanced (per currency) and trg_wallet_non_negative.';

COMMIT;
