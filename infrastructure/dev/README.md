# infrastructure/dev — local stack configuration

Everything `docker-compose.yml` needs that is not an image or a build:
the Postgres bootstrap, the NATS server config and the Keycloak realm
import. `docs/DEV-STACK.md` is the operator manual (ports, credentials,
boot sequence, troubleshooting); this file explains what each artifact is
and who consumes it.

```
infrastructure/dev/
├── postgres/
│   └── init/
│       └── 01-schemas.sql     # executed once on a fresh pgdata volume
├── nats/
│   └── nats-server.conf       # mounted read-only into the nats container
├── keycloak/
│   └── sharkpay-realm.json    # realm "sharkpay", imported at every boot
└── README.md
```

## postgres/init/01-schemas.sql

Runs via `docker-entrypoint-initdb.d` (autocommit, `ON_ERROR_STOP=1`) the
first time the `pgdata` volume is created — never on plain restarts.
Passwords are injected as container env (from `.env` / compose defaults)
and read with psql's `\getenv`, so the file itself contains no secrets.

What it provisions (storage only — tables belong to each service's own
migrations):

- LOGIN roles `sharkpay_<service>` (+ `temporal`), each with a password
  from env;
- one schema per service in the shared `sharkpay` database —
  `ledger, identity, wallet, risk, fx, payments, payouts, api_gateway,
  reconciliation` — **owned** by the matching role (full rights on that
  schema, nothing on any other), with `search_path` pinned per role so
  Flyway/Hibernate resolve unqualified names correctly with a plain
  `jdbc:…/sharkpay` URL;
- databases `temporal` and `temporal_visibility` owned by role `temporal`
  (Temporal's SQL tooling requires dedicated databases; the
  `temporalio/auto-setup` container runs with `SKIP_DB_CREATE=true` and
  only builds its schemas inside them).

Idempotent (`\gexec` + `NOT EXISTS` guards) so it is safe to re-run
manually: `docker compose exec postgres psql -U sharkpay -d sharkpay -f
/docker-entrypoint-initdb.d/01-schemas.sql`.

## nats/nats-server.conf

Single-node dev NATS with JetStream: client `:4222`, monitoring `:8222`,
no auth (compose-network only), file store in `/data` (the `nats_data`
volume), memory cap 512 MiB, file cap 10 GiB. Syntax-check without a
daemon: `nats-server -t -c infrastructure/dev/nats/nats-server.conf`.
Streams are runtime objects — see docs/DEV-STACK.md § "Creating JetStream
streams".

## keycloak/sharkpay-realm.json

Realm `sharkpay` imported by `start-dev --import-realm` on **every** boot
(Keycloak uses an ephemeral dev database — no volume, so live-console
edits are lost by design; change the JSON, not the running realm).
Contents:

- public OIDC clients `sharkpay-web` (Authorization Code + PKCE S256,
  redirects `http://localhost:3000/*`, password grant enabled for dev
  token scripting) and `sharkpay-mobile` (PKCE S256, custom scheme
  `sharkpay-mobile://callback` + Expo Go dev URIs);
- confidential `client_credentials` clients for identity / wallet / risk /
  fx / payments / payouts / api-gateway / reconciliation, each with a
  service account mapped to `service.<domain>.<read|write>` realm roles
  (consumer-driven: a client gets roles for what it *calls*);
- demo users `ops` / `merchant` / `agent` @sharkpay.dev with realm roles
  and dev-only passwords.

Secrets are the dev literals mirrored in `.env.example`. Validate:
`python3 -m json.tool infrastructure/dev/keycloak/sharkpay-realm.json`.

## Out of scope here

- `docker-compose.yml` itself — repo root (dev-stack workstream owns it);
- the OpenTelemetry collector / Prometheus / Grafana / Loki / Tempo —
  separate compose overlay from the observability workstream;
- Kubernetes/Helm/Terraform for real environments — `infrastructure/`
  siblings when those work packages land.
