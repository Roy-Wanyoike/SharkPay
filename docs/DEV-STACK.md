# Dev Stack — local environment (ADR 001 parity)

One `docker compose up -d` brings up the full SharkPay local stack:
**Postgres 18** (schema-per-service), **NATS 2.11 JetStream** (CloudEvents
transport), **Keycloak 26** (realm `sharkpay`), **Temporal 1.28 + UI**,
**Redis 7** (cache only), **WireMock** (HoneyCoin simulator) and every
service built so far (Go `ledger`/`providers`, Java `identity`/`wallet`/
`fx`/`risk`).

Configs live in [`infrastructure/dev/`](../infrastructure/dev/) — that
directory is the source of truth for the Postgres bootstrap, the NATS
server config and the Keycloak realm import. All credentials in this file
are **dev-only literals** shared by `.env.example`, the postgres init SQL
and the Keycloak realm JSON.

---

## Ports

| Host port | Service | In-container | Notes |
|---|---|---|---|
| 5432 | postgres | 5432 | shared DB `sharkpay`, schema per service |
| 4222 | nats | 4222 | client port — `NATS_URL=nats://nats:4222` |
| 8222 | nats monitor | 8222 | `http://localhost:8222/` (healthz, varz, jsz) |
| 6379 | redis | 6379 | cache only — no persistence |
| 8080 | keycloak | 8080 | `http://localhost:8080/admin`, realm `sharkpay` |
| 7233 | temporal | 7233 | gRPC (Temporal Java SDK workers) |
| 8181 | temporal-ui | 8080 | `http://localhost:8181` |
| 8081 | wiremock | 8080 | fake HoneyCoin (`/__admin` console) |
| 8090 | ledger (Go) | 8090 | `GET /healthz`, `GET /readyz` |
| 8091 | providers (Go) | 8091 | `GET /healthz` |
| 8082 | identity (Java) | 8081 | `/actuator/health` |
| 8083 | wallet (Java) | 8082 | `/actuator/health` |
| 8084 | fx (Java) | 8083 | `/actuator/health` |
| 8085 | risk (Java) | 8084 | `/actuator/health` |
| 3000 | web app (host-side) | — | not containerized yet; Keycloak redirect URIs already expect it |

Java host ports = container port + 1, because identity's in-container 8081
collides with the long-standing wiremock host port 8081 (kept stable for
`make compose-up` output and `tests/wiremock` docs).

**Reserved for Wave 3** (schema/role/Keycloak client already exist):
8086 payments · 8087 payouts · 8088 api-gateway · 8089 reconciliation.

The OpenTelemetry collector (+ Prometheus/Grafana/Loki/Tempo) is **not** in
this file — it ships as a separate compose overlay from the observability
workstream. Services already receive `OTEL_EXPORTER_OTLP_ENDPOINT`
(`http://otel-collector:4318`) so the overlay drops in without editing
`docker-compose.yml`; `docker-compose.yml` is kept free of observability containers on purpose; merge
the overlay file with whatever name that workstream ships when it lands:

```bash
docker compose -f docker-compose.yml -f <observability-overlay.yml> up -d
```

## Seeded credentials (dev only)

### Postgres (created by `infrastructure/dev/postgres/init/01-schemas.sql`)

| Role | Password | Schema (owned, search_path) |
|---|---|---|
| `sharkpay` (superuser) | `sharkpay-dev` | all (dev admin) |
| `sharkpay_ledger` | `ledger-dev-pass` | `ledger` |
| `sharkpay_identity` | `identity-dev-pass` | `identity` |
| `sharkpay_wallet` | `wallet-dev-pass` | `wallet` |
| `sharkpay_risk` | `risk-dev-pass` | `risk` |
| `sharkpay_fx` | `fx-dev-pass` | `fx` |
| `sharkpay_payments` | `payments-dev-pass` | `payments` |
| `sharkpay_payouts` | `payouts-dev-pass` | `payouts` |
| `sharkpay_api_gateway` | `api-gateway-dev-pass` | `api_gateway` |
| `sharkpay_reconciliation` | `reconciliation-dev-pass` | `reconciliation` |
| `temporal` | `temporal-dev-pass` | databases `temporal`, `temporal_visibility` |

JDBC URL for every Java service is the *shared* database:
`jdbc:postgresql://postgres:5432/sharkpay` — isolation comes from the
per-service LOGIN role + its pinned `search_path`, not from per-service
databases (one DB, one schema per service, grants on own schema only).

### Keycloak (realm `sharkpay`, imported from the realm JSON)

| Principal | Secret | Notes |
|---|---|---|
| master admin | `admin` / `admin-dev-pass` | console at `http://localhost:8080/admin` |
| `ops@sharkpay.dev` | `sharkpay-dev-ops` | realm role `ops` |
| `merchant@sharkpay.dev` | `sharkpay-dev-merchant` | realm role `merchant` |
| `agent@sharkpay.dev` | `sharkpay-dev-agent` | realm role `agent` |
| client `sharkpay-web` | (public) | PKCE S256, redirect `http://localhost:3000/*` |
| client `sharkpay-mobile` | (public) | PKCE S256, `sharkpay-mobile://callback` + Expo dev URIs |
| client `sharkpay-identity` | `identity-service-dev-secret` | confidential, service account |
| client `sharkpay-wallet` | `wallet-service-dev-secret` | confidential, service account |
| client `sharkpay-risk` | `risk-service-dev-secret` | confidential, service account |
| client `sharkpay-fx` | `fx-service-dev-secret` | confidential, service account |
| client `sharkpay-payments` | `payments-service-dev-secret` | confidential, service account |
| client `sharkpay-payouts` | `payouts-service-dev-secret` | confidential, service account |
| client `sharkpay-api-gateway` | `api-gateway-service-dev-secret` | confidential, service account |
| client `sharkpay-reconciliation` | `reconciliation-service-dev-secret` | confidential, service account |

Service-account tokens carry the `service.<domain>.<read|write>` realm
roles listed in the realm JSON (e.g. `sharkpay-payments` ⇒
`service.payments.write`, `service.ledger.write`, `service.wallet.write`,
`service.risk.write`, `service.fx.read`).

### Go services / tools

| Var | Dev value |
|---|---|
| `INTERNAL_API_TOKEN` (ledger) | `dev-internal-token` |
| `HONEYCOIN_SIGNING_KEY` | `dev-honeycoin-key` |
| `HONEYCOIN_CALLBACK_SECRET` | `dev-callback-secret` |
| `HONEYCOIN_BASE_URL` | `http://wiremock:8080` |

Everything above is also in [`.env.example`](../.env.example) — copy it to
`.env` to override any value without touching the compose file.

## Boot sequence

```bash
cp .env.example .env      # optional (every default is inlined anyway)
docker compose up -d      # infra + all built services

# watch it come up (keycloak realm import takes ~30-60s on first boot)
docker compose ps
docker compose logs -f keycloak
```

Dependency graph enforced by `depends_on` (all healthcheck-gated where a
probe exists):

```
postgres ── temporal ── temporal-ui
    │
    ├─ identity · wallet · fx · risk ── keycloak (issuer fetch)
    │            └─────────────────── nats (NATS_URL wiring)
    └─ ledger (memory store; postgres URL pre-wired)

wiremock ── providers
nats · redis            (standalone)
```

Healthcheck notes (no Docker daemon needed to understand them):

- **Java services**: `GET /actuator/health` probed with raw HTTP/1.1 via
  bash `/dev/tcp` (the Temurin JRE image ships no curl/wget). Returns 200
  only when the datasource + OIDC config are live. Each container sets
  `HEALTHCHECK_PORT` to its own `server.port` (identity 8081, wallet 8082,
  fx 8083, risk 8084) — container plumbing, not settable from `.env`.
- **providers** (alpine): `wget http://localhost:8091/healthz` — the path
  comes from `services/providers/cmd/server/main.go`.
- **ledger**: `services/ledger/Dockerfile` is distroless/static — no shell,
  no probe binary, so **no in-container healthcheck is possible**. It does
  expose `GET /healthz` + `GET /readyz`: probe from the host
  (`curl http://localhost:8090/healthz`) or from nats-box.
- **temporal server**: the auto-setup image has no probe client either;
  `temporal-ui` (healthchecked) is the visible signal.

## Verifying the whole stack

```bash
docker compose ps                              # every container Up (healthy)

# probes
curl -s localhost:8090/healthz                 # ledger
curl -s localhost:8091/healthz                 # providers
curl -s localhost:8083/actuator/health         # wallet (8082 identity, 8084 fx, 8085 risk)
curl -s localhost:8222/healthz                 # nats
curl -s localhost:8080/realms/sharkpay/.well-known/openid-configuration

# postgres: schemas + roles
docker compose exec postgres psql -U sharkpay -d sharkpay \
  -c '\dn' -c "SELECT rolname FROM pg_roles WHERE rolname LIKE 'sharkpay_%' ORDER BY 1"

# JetStream account report
docker compose --profile tools run --rm nats-box \
  nats -s nats://nats:4222 account info

# Temporal namespaces (tctl ships in most auto-setup tags; if your image
# dropped it, install the temporal CLI and point it at localhost:7233)
docker compose exec temporal tctl namespace list
```

**`scripts/verify-all.sh`** is the ADR 003 gate ladder (contracts →
Go build/vet/fmt/test → `mvn -B clean verify` per Java module → coverage
gates). It validates the *code*; use it before any PR:

```bash
scripts/verify-all.sh                 # full ladder (exit 0 = green)
scripts/verify-all.sh --skip-web      # web app not scaffolded yet
```

For compose itself, `docker compose config -q` is the structural check
used by the integrator (G5, ADR 003 §4) — no daemon required.

## Volume reset (clean slate)

```bash
docker compose down -v          # removes pgdata + nats_data (schemas,
                                # schemas' Flyway history, JetStream streams)
```

Notes:

- Redis and Keycloak are **ephemeral by design** — no volumes at all.
  Keycloak re-imports `infrastructure/dev/keycloak/sharkpay-realm.json` on
  every restart: edit the JSON (never the live realm) and restart.
- After a password change in `.env`, a plain restart will NOT re-run
  `01-schemas.sql` (init scripts execute only on a fresh volume):
  `docker compose down -v && docker compose up -d`.
- `docker compose down` (no `-v`) keeps pgdata/nats_data across rebuilds.

## Creating JetStream streams

Streams are runtime objects — the NATS config only enables JetStream
(`store_dir /data`, memory/file caps). Create them from nats-box against
the topic catalog in [`contracts/events/events.md`](../contracts/events/events.md):

```bash
docker compose --profile tools run --rm nats-box

# authoritative money feed (consumed by wallet + reconciliation + api-gateway)
nats stream add LEDGER \
  --subjects 'ledger.posting.committed.v1' \
  --storage file --retention limits --discard old \
  --max-age 168h --dupe-window 2m --replicas 1

# one stream per domain, subjects straight from the catalog
nats stream add PAYMENTS  --subjects 'payments.payment.*.v1'  --storage file --retention limits
nats stream add PAYOUTS   --subjects 'payouts.payout.*.v1,transfers.transfer.*.v1' --storage file --retention limits
nats stream add WALLET    --subjects 'wallet.balance.changed.v1' --storage file --retention limits
nats stream add RISK      --subjects 'risk.*.v1' --storage file --retention limits
nats stream add FX        --subjects 'fx.*.v1'  --storage file --retention limits

nats stream ls
nats stream info LEDGER
nats sub --count 5 ledger.posting.committed.v1   # watch the money feed
```

Delivery semantics stay at-least-once with `id` dedup (CloudEvents
envelope, events.md rules 1–4).

## Switching the ledger to Postgres

The dev default keeps `LEDGER_STORE=memory` (the ledger's documented
dev-only fake). For a DB-backed ledger run:

1. Apply the ledger's own migration (it creates tables **and** the
   append-only `sharkpay_app` role) into the `ledger` schema:

   ```bash
   docker compose exec -T postgres psql -U sharkpay_ledger -d sharkpay \
     -v ON_ERROR_STOP=1 \
     -f - < services/ledger/migrations/001_ledger_init.sql
   ```

2. Set in `.env`: `LEDGER_STORE=postgres`
   (`LEDGER_DATABASE_URL` is already pre-wired with the `sharkpay_ledger`
   credentials and `sslmode=disable`).
3. `docker compose up -d ledger`.

## Getting a service token (two issuer values)

- `KEYCLOAK_EXTERNAL_ISSUER` = `http://localhost:8080/realms/sharkpay` —
  what your **browser/curl on the host** sees (login, admin console).
- `KEYCLOAK_INTERNAL_ISSUER` = `http://keycloak:8080/realms/sharkpay` —
  what the **resource-server services** use (they fetch OIDC discovery over
  the compose network; `localhost` inside a container is the container).

A token's `iss` must equal the *service-side* issuer, so machine tokens
must be minted **from inside the network**:

```bash
docker compose --profile tools run --rm nats-box curl -s \
  -d client_id=sharkpay-payments \
  -d client_secret=payments-service-dev-secret \
  -d grant_type=client_credentials \
  http://keycloak:8080/realms/sharkpay/protocol/openid-connect/token

# demo (human) users — password grant on the public web client:
curl -s -d client_id=sharkpay-web \
  -d username=ops@sharkpay.dev -d password=sharkpay-dev-ops \
  -d grant_type=password -d scope=openid \
  http://localhost:8080/realms/sharkpay/protocol/openid-connect/token
```

Tokens minted via `localhost` carry `http://localhost:8080/...` as `iss`
and will be **rejected** by the in-network services (401) — that is
correct behavior, not a bug.

## Troubleshooting

| Symptom | Cause / fix |
|---|---|
| Keycloak `unhealthy` forever, logs show realm import errors | Inspect `docker compose logs keycloak`. The realm JSON is re-imported at every boot (ephemeral dev DB) — fix the JSON, `docker compose restart keycloak`. Validate the file: `python3 -m json.tool infrastructure/dev/keycloak/sharkpay-realm.json`. |
| Keycloak healthcheck says `bash: not found` | The probe is pure bash (`/dev/tcp`), which the UBI-micro image ships. If a future image drops bash, replace the healthcheck in docker-compose.yml with `depends_on`-free `service_started` semantics, or add a sidecar prober. |
| Java service restarts with `Validation of schema/query failed` (Hibernate) or Flyway logs `no migrations found` | The service's `db/migration` Flyway scripts have not landed yet (e.g. `V1__wallet_init.sql`). `ddl-auto: validate` refuses to boot against an empty schema. Land the migration (or temporarily set `spring.jpa.hibernate.ddl-auto=none` + `spring.flyway.enabled=false` in that service's profile) — stack wiring itself is correct. |
| `mvn` inside `docker compose build` fails to resolve `com.sharkpay:sharkpay-money` | The money lib lives in `packages/java/sharkpay-money` and is only in the workspace's local `~/.m2`. CI must publish it (internal registry) or bake it into a base image — noted in every Java Dockerfile. |
| `identity/wallet/fx/risk` unhealthy, logs show issuer fetch failure | Keycloak wasn't healthy yet — they wait on `keycloak: service_healthy`, but a mid-boot Keycloak restart can race: `docker compose restart identity wallet fx risk`. |
| Temporal UI empty / `tctl` can't connect | `docker compose logs temporal` — the auto-setup container creates its schemas once; if `01-schemas.sql` didn't create the `temporal` role/DBs (old volume), reset: `docker compose down -v && docker compose up -d`. |
| `nats stream ls` shows no streams | Streams are created per § Creating JetStream streams (not by config). JetStream health: `curl localhost:8222/jsz`. |
| `postgres` init failed: `syntax error near "\gexec"` | You re-ran the SQL with a psql older than 15 (`\getenv`/`\gexec` need ≥15). The container ships psql 18; run it via `docker compose exec postgres psql …`. |
| Port already in use | The stack owns 5432/4222/6379/8080/7233/8181/8081/8090/8091/8082–8085 on localhost. Stop the conflicting process or remap the *host* side only in `docker-compose.override.yml` (do not edit tracked defaults). |
| `docker compose up` pulled an older stack (Redpanda, postgres:16) | Old volume from the Wave 1 compose: `docker compose down -v` (Redpanda is gone per ADR 001 note 3 — the dev stack is NATS-only now). |

## Makefile compatibility

`make compose-up` / `compose-down` / `compose-ps` still work — the ledger
(8090), providers (8091), wiremock (8081) and temporal-ui (8181) host
ports from the Wave 1 stack are unchanged.
