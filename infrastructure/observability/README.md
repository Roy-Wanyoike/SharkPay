# SharkPay Observability Infrastructure

Everything that collects, stores, alerts on and visualizes telemetry.
Contract and conventions live in [docs/OBSERVABILITY.md](../../docs/OBSERVABILITY.md) —
this README is the operator's quickstart.

## Boot (overlay — never standalone)

The observability stack is a Docker Compose **overlay** on the root dev
stack. Always bring it up together with the base file:

```bash
docker compose -f docker-compose.yml \
                -f infrastructure/observability/compose.observability.yml \
                up -d
```

The overlay declares no networks and no project name, so its services join
the merged project's default network — the same one as postgres, nats,
keycloak, temporal, redis, ledger, providers and the Java services in the
root compose. The root compose already sets
`OTEL_EXPORTER_OTLP_ENDPOINT=http://otel-collector:4318` (OTLP/HTTP) on
every app service — the `otel-collector` service defined here matches that
name, so telemetry starts flowing as soon as services ship their OTel SDKs.
Host-run processes (IDE, local build) can use `http://localhost:4318`
(HTTP) or `http://localhost:4317` (gRPC).

Shut it down (keeps base stack):

```bash
docker compose -f docker-compose.yml \
                -f infrastructure/observability/compose.observability.yml \
                stop prometheus grafana loki tempo alertmanager otel-collector
```

| Host port | UI | Notes |
|---|---|---|
| 3001 | Grafana | admin / `sharkpay-dev` (dev credential), anonymous viewer; home dashboard = Platform Overview |
| 9091 | Prometheus | Status → Rules / Targets / Alerts |
| 9093 | Alertmanager | routing preview; receiver URLs are dev placeholders (below) |
| 4317/4318 | OTLP gRPC/HTTP | for host-run processes |
| 3100 | Loki | `/ready`, `/metrics` (not a UI) |
| 3200 | Tempo | query API (used by Grafana's Tempo datasource) |

## File map

```
infrastructure/observability/
├── compose.observability.yml        # the overlay (6 services + 5 volumes, healthchecks)
├── otelcol/
│   └── otel-collector-config.yaml   # OTLP in (4317/4318) → prometheus exporter :9090,
│                                    #   Loki OTLP/gRPC :9096, Tempo :4317, debug
├── prometheus/
│   ├── prometheus.yml               # scrapes otel-collector:9090 + self + alertmanager;
│                                    #   rule files; alerting → alertmanager:9093
│   └── rules/
│       ├── recording-rules.yml      # RED per service (Java+Go name union), SLO windows,
│       │                            #   money-path + consumer-lag recordings
│       └── alert-rules.yml          # 23 alerts: burn rates, money path, JVM, self-monitoring
├── alertmanager/
│   └── alertmanager.yml             # page/critical/warning routing, observability team,
│                                    #   grouping + inhibition (critical ⊣ warning)
├── grafana/
│   ├── provisioning/
│   │   ├── datasources/datasources.yml   # prometheus, loki, tempo (fixed uids; loki↔tempo links)
│   │   └── dashboards/dashboards.yml     # file provider → folder "SharkPay"
│   └── dashboards/
│       ├── platform-overview.json
│       ├── ledger-posting.json
│       ├── wallet-projections.json
│       ├── payments-lifecycle.json
│       ├── providers-gateway.json
│       └── jvm-runtime.json
├── loki/
│   └── loki-config.yaml             # tsdb v13, filesystem, 7d retention, OTLP ingest
└── tempo/
    └── tempo-config.yaml            # OTLP receiver, local backend, 7d retention
```

## How to add a dashboard

1. Drop a `.json` file in `grafana/dashboards/` (any name; the provider
   loads everything in the folder).
2. Requirements so it fits the platform:
   - `"uid": "sharkpay-<name>"` (stable links), `"tags": ["sharkpay"]`.
   - Datasource references `{"type": "prometheus", "uid": "prometheus"}`
     (or `loki` / `tempo`).
   - A `service` templating variable:
     `label_values(<any platform metric>, service_name)`, and use
     `{service_name=~"$service"}` in queries.
   - Thresholds at SLO values (99.9% availability, 500ms API, 200ms posting,
     5s projection lag, 99.5% webhook) so red means breach.
3. `python3 -c "import json;json.load(open('grafana/dashboards/<name>.json'))"`
   must pass, then reload Grafana (it picks up new files within 30s) or
   `docker compose ... restart grafana`.
4. Prefer recording rules (`prometheus/rules/recording-rules.yml`) over raw
   expressions when a query is used 3+ times — rules are the shared surface.

## Validation (no Docker required — run from repo root)

```bash
# every YAML file parses
python3 - <<'PY'
import glob, yaml
for f in glob.glob('infrastructure/observability/**/*.y*ml', recursive=True):
    yaml.safe_load(open(f)); print('ok', f)
PY

# every dashboard is valid JSON
python3 - <<'PY'
import glob, json
for f in glob.glob('infrastructure/observability/grafana/dashboards/*.json'):
    json.load(open(f)); print('ok', f)
PY
```

`amtool check-config` / `promtool check rules` additionally validate the
alertmanager and rule files when those binaries are available (they are not
installed in the sandbox; the integrator runs them in CI).

## Known dev-only caveats (fix before prod)

- **Alertmanager receivers** point at `http://localhost:9999/{page,ticket,observability}`.
  Nothing listens there in dev — Alertmanager routes and groups normally,
  it just logs dial errors. Replace with PagerDuty/JSM/Slack URLs from
  Secrets Manager.
- **Alert runbooks** link to `docs/RUNBOOKS.md` anchors; that file is being
  authored by the ops-docs track.
- **Healthchecks** assume `wget` (busybox-style) in the prometheus, loki,
  alertmanager, tempo and collector images and `curl` in the grafana image —
  the common convention for those images; if an image ships without the
  tool, docker marks it unhealthy without crashing (swap for the image's
  bundled CLI at integration time).
- **No TLS/auth** between collector and backends (dev network); production
  adds OTLP auth headers + TLS (docs/OBSERVABILITY.md §13).
- If the root compose grows **custom networks**, otel-collector must be
  attached to the app network so services can resolve `otel-collector:4317`
  — a one-line integration-time addition, kept out of this overlay on
  purpose so it never fights the root file.
