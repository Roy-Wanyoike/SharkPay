-- 01-schemas.sql — SharkPay dev Postgres bootstrap (ADR 001: PostgreSQL 18).
--
-- Executed exactly ONCE by the postgres entrypoint on a fresh pgdata volume
-- (docker-entrypoint-initdb.d, ON_ERROR_STOP=1). It provisions namespaced
-- storage ONLY — never tables. Tables are owned by each service's own
-- migrations (Flyway classpath:db/migration for the Java services,
-- services/ledger/migrations/001_ledger_init.sql for ledger).
--
-- Model (services/README.md "one schema per service, never another
-- service's tables"):
--   * database "sharkpay" (POSTGRES_DB) hosts one schema per service:
--       ledger, identity, wallet, risk, fx, payments, payouts,
--       api_gateway, reconciliation
--   * each schema is OWNED by its LOGIN role  sharkpay_<service>  (password
--     injected via container env, read below with psql's \getenv — psql >= 15,
--     postgres:18-alpine ships psql 18). Ownership ⇒ full rights on that
--     schema and nothing else; no cross-schema grants anywhere.
--   * each role gets a pinned search_path so unqualified DDL/DML from
--     Flyway and Hibernate (spring.jpa.hibernate.ddl-auto=validate) lands
--     in its own schema without per-connection URL parameters.
--   * Temporal cannot share the app database (its SQL tooling wants
--     dedicated databases), so the roles list also covers Temporal:
--     databases "temporal" and "temporal_visibility" are created here and
--     owned by the "temporal" role; the temporalio/auto-setup container
--     runs with SKIP_DB_CREATE=true and only sets up its schemas inside
--     them. That is the "temporal schema" entry of the stack table.
--
-- Passwords: injected from docker-compose.yml (postgres.environment) which
-- defaults them from .env.example. Changing a password in .env requires a
-- volume reset (docker compose down -v) for it to reach a fresh init.
--
-- Idempotency: roles/databases are guarded (\gexec + NOT EXISTS) so this
-- file is also safe to re-run manually against an existing volume:
--   docker compose exec postgres psql -U sharkpay -d sharkpay \
--     -f /docker-entrypoint-initdb.d/01-schemas.sql

\getenv ledger_pw          LEDGER_DB_PASSWORD
\getenv identity_pw        IDENTITY_DB_PASSWORD
\getenv wallet_pw          WALLET_DB_PASSWORD
\getenv risk_pw            RISK_DB_PASSWORD
\getenv fx_pw              FX_DB_PASSWORD
\getenv payments_pw        PAYMENTS_DB_PASSWORD
\getenv payouts_pw         PAYOUTS_DB_PASSWORD
\getenv api_gateway_pw     API_GATEWAY_DB_PASSWORD
\getenv reconciliation_pw  RECONCILIATION_DB_PASSWORD
\getenv temporal_pw        TEMPORAL_DB_PASSWORD

-- ---------------------------------------------------------------------------
-- 1. Per-service LOGIN roles (idempotent via \gexec)
-- ---------------------------------------------------------------------------
SELECT 'CREATE ROLE sharkpay_ledger LOGIN PASSWORD ' || quote_literal(:'ledger_pw')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'sharkpay_ledger')\gexec
SELECT 'CREATE ROLE sharkpay_identity LOGIN PASSWORD ' || quote_literal(:'identity_pw')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'sharkpay_identity')\gexec
SELECT 'CREATE ROLE sharkpay_wallet LOGIN PASSWORD ' || quote_literal(:'wallet_pw')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'sharkpay_wallet')\gexec
SELECT 'CREATE ROLE sharkpay_risk LOGIN PASSWORD ' || quote_literal(:'risk_pw')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'sharkpay_risk')\gexec
SELECT 'CREATE ROLE sharkpay_fx LOGIN PASSWORD ' || quote_literal(:'fx_pw')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'sharkpay_fx')\gexec
SELECT 'CREATE ROLE sharkpay_payments LOGIN PASSWORD ' || quote_literal(:'payments_pw')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'sharkpay_payments')\gexec
SELECT 'CREATE ROLE sharkpay_payouts LOGIN PASSWORD ' || quote_literal(:'payouts_pw')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'sharkpay_payouts')\gexec
SELECT 'CREATE ROLE sharkpay_api_gateway LOGIN PASSWORD ' || quote_literal(:'api_gateway_pw')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'sharkpay_api_gateway')\gexec
SELECT 'CREATE ROLE sharkpay_reconciliation LOGIN PASSWORD ' || quote_literal(:'reconciliation_pw')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'sharkpay_reconciliation')\gexec
SELECT 'CREATE ROLE temporal LOGIN PASSWORD ' || quote_literal(:'temporal_pw')
WHERE NOT EXISTS (SELECT FROM pg_roles WHERE rolname = 'temporal')\gexec

-- ---------------------------------------------------------------------------
-- 2. One schema per service, owned by that service's role
--    (AUTHORIZATION ⇒ owner: full rights on the schema; grants to no one else)
-- ---------------------------------------------------------------------------
CREATE SCHEMA IF NOT EXISTS ledger          AUTHORIZATION sharkpay_ledger;
CREATE SCHEMA IF NOT EXISTS identity         AUTHORIZATION sharkpay_identity;
CREATE SCHEMA IF NOT EXISTS wallet           AUTHORIZATION sharkpay_wallet;
CREATE SCHEMA IF NOT EXISTS risk             AUTHORIZATION sharkpay_risk;
CREATE SCHEMA IF NOT EXISTS fx               AUTHORIZATION sharkpay_fx;
CREATE SCHEMA IF NOT EXISTS payments         AUTHORIZATION sharkpay_payments;
CREATE SCHEMA IF NOT EXISTS payouts          AUTHORIZATION sharkpay_payouts;
CREATE SCHEMA IF NOT EXISTS api_gateway      AUTHORIZATION sharkpay_api_gateway;
CREATE SCHEMA IF NOT EXISTS reconciliation   AUTHORIZATION sharkpay_reconciliation;

COMMENT ON SCHEMA ledger        IS 'SharkPay ledger (Go WP-3): chart of accounts + append-only journal. DDL: services/ledger/migrations.';
COMMENT ON SCHEMA identity      IS 'SharkPay identity (Java WP-1): principals, SharkID, KYC, devices. DDL: services/identity Flyway.';
COMMENT ON SCHEMA wallet        IS 'SharkPay wallet (Java WP-2): wallets, holds, balance projection. DDL: services/wallet Flyway.';
COMMENT ON SCHEMA risk          IS 'SharkPay risk (Java WP-8): evaluations, cases, velocity counters, rule sets. DDL: services/risk Flyway.';
COMMENT ON SCHEMA fx            IS 'SharkPay fx (Java WP-7): quotes, conversions, positions. DDL: services/fx Flyway.';
COMMENT ON SCHEMA payments      IS 'SharkPay payments (Java WP-5, Temporal-orchestrated). Reserved until the service lands.';
COMMENT ON SCHEMA payouts       IS 'SharkPay payouts (Java WP-6). Reserved until the service lands.';
COMMENT ON SCHEMA api_gateway   IS 'SharkPay public API gateway + webhooks (Java WP-9). Reserved until the service lands.';
COMMENT ON SCHEMA reconciliation IS 'SharkPay reconciliation (Java WP-10). Reserved until the service lands.';

-- ---------------------------------------------------------------------------
-- 3. Pin each role's default search_path to its own schema
--    (Flyway/Hibernate resolve unqualified names correctly with zero
--    connection-string parameters; jdbc:…/sharkpay keeps working.)
-- ---------------------------------------------------------------------------
ALTER ROLE sharkpay_ledger          SET search_path = ledger;
ALTER ROLE sharkpay_identity        SET search_path = identity;
ALTER ROLE sharkpay_wallet          SET search_path = wallet;
ALTER ROLE sharkpay_risk            SET search_path = risk;
ALTER ROLE sharkpay_fx              SET search_path = fx;
ALTER ROLE sharkpay_payments        SET search_path = payments;
ALTER ROLE sharkpay_payouts         SET search_path = payouts;
ALTER ROLE sharkpay_api_gateway     SET search_path = api_gateway;
ALTER ROLE sharkpay_reconciliation  SET search_path = reconciliation;

-- Belt & suspenders (schema ownership already grants these; made explicit
-- so the GRANT matrix is greppable in reviews):
GRANT USAGE, CREATE ON SCHEMA ledger          TO sharkpay_ledger;
GRANT USAGE, CREATE ON SCHEMA identity        TO sharkpay_identity;
GRANT USAGE, CREATE ON SCHEMA wallet          TO sharkpay_wallet;
GRANT USAGE, CREATE ON SCHEMA risk            TO sharkpay_risk;
GRANT USAGE, CREATE ON SCHEMA fx              TO sharkpay_fx;
GRANT USAGE, CREATE ON SCHEMA payments        TO sharkpay_payments;
GRANT USAGE, CREATE ON SCHEMA payouts         TO sharkpay_payouts;
GRANT USAGE, CREATE ON SCHEMA api_gateway     TO sharkpay_api_gateway;
GRANT USAGE, CREATE ON SCHEMA reconciliation  TO sharkpay_reconciliation;

-- Postgres 15+ already removed PUBLIC's CREATE on schema public; dev DBs
-- keep public CONNECT for convenience. Isolation is at schema level.

-- ---------------------------------------------------------------------------
-- 4. Temporal databases (owned by the temporal role; auto-setup then only
--    creates its tables/schemas inside them — SKIP_DB_CREATE=true)
--    NOTE: CREATE DATABASE cannot run inside a transaction, hence \gexec
--    top-level statements, and this file deliberately contains no
--    BEGIN/COMMIT wrapper.
-- ---------------------------------------------------------------------------
SELECT 'CREATE DATABASE temporal OWNER temporal'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'temporal')\gexec
SELECT 'CREATE DATABASE temporal_visibility OWNER temporal'
WHERE NOT EXISTS (SELECT FROM pg_database WHERE datname = 'temporal_visibility')\gexec

-- ---------------------------------------------------------------------------
-- 5. Done. Sanity summary when run manually:
-- ---------------------------------------------------------------------------
-- SELECT rolname FROM pg_roles WHERE rolname LIKE 'sharkpay_%' ORDER BY 1;
-- SELECT schema_name FROM information_schema.schemata
--  WHERE schema_name NOT IN ('pg_catalog','information_schema') ORDER BY 1;
