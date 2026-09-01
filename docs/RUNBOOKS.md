# SharkPay — Operations Runbooks

| | |
|---|---|
| **Status** | Canonical — alert→procedure contract for the observability stack |
| **Owner** | @Roy-Wanyoike · on-call rotation per RB-9 |
| **Companions** | [OBSERVABILITY](OBSERVABILITY.md) (alerts & SLOs) · [BACKEND-DESIGN](BACKEND-DESIGN.md) · [SECURITY](SECURITY.md) §7 (incident response) · [infrastructure/observability/README](../infrastructure/observability/README.md) |

Every alert in `infrastructure/observability/prometheus/rules/alert-rules.yml`
carries `runbook_url: docs/RUNBOOKS.md#<lowercase-alertname>`. **An alert
without a working runbook anchor is a release blocker.** The nine anchors
the stack links to are the lowercase alert-name headings below; the full
procedures are RB-1…RB-9.

---

## 1. Conventions & toolbox

**Environments.** `dev` = docker compose (`docker-compose.yml` + optional
`infrastructure/observability/compose.observability.yml` overlay);
`staging`/`prod` = Kubernetes (`infrastructure/k8s/overlays/{staging,prod}`,
namespace `sharkpay`, Argo CD managed). Prod examples below use
`kubectl -n sharkpay`; dev examples use plain `curl`/`psql`/`docker compose`.

**Service registry & dev ports** (OBSERVABILITY §4.1; wiremock owns 8081 so
Java services publish to 8082–8085):

| Service | service.name | Dev port | Health |
|---|---|---|---|
| ledger (Go) | `sharkpay-ledger` | 8090 | `GET /healthz`, `GET /readyz` |
| providers (Go) | `sharkpay-providers` | 8091 | `GET /healthz` |
| identity (Java) | `sharkpay-identity` | 8082 | `GET /actuator/health` |
| wallet (Java) | `sharkpay-wallet` | 8083 | `GET /actuator/health` |
| fx (Java) | `sharkpay-fx` | 8084 | `GET /actuator/health` |
| risk (Java) | `sharkpay-risk` | 8085 | `GET /actuator/health` |
| payments / payouts / api-gateway / reconciliation | planned | — | phases 5/6/9/10 |

**Shared infra (dev ports):** Postgres 5432 (schema per service,
`psql "$DATABASE_URL"`), NATS 4222 / monitor 8222, Redis 6379, Keycloak 8080
(realm `sharkpay`), Temporal 7233 / UI 8181, wiremock HoneyCoin 8081,
Prometheus UI 9091, Alertmanager 9093, Grafana 3001, Loki 3100, Tempo 3200.

**Toolbox** (run from repo root; env-specific values in `[]`):

```bash
# SQL against any service schema (dev)
psql "postgres://sharkpay:[pw]@localhost:5432/sharkpay" -c 'SET search_path TO ledger; ...'

# NATS JetStream (dev: inside the compose network; k8s: nats-box pod)
nats --server nats://localhost:4222 stream info LEDGER_POSTING
nats --server nats://localhost:4222 consumer info LEDGER_POSTING wallet-projection
nats stream view <DLQ> --all          # drain inspection

# Temporal (dev server localhost:7233; prod namespace per env)
temporal --address localhost:7233 workflow show --workflow-id 'payment:<intent_id>'
temporal workflow list --query 'ExecutionStatus = "Running" AND StartTime < now()-24h'

# Argo Rollouts (prod/staging; money path = Rollouts, rest = Deployments)
kubectl argo rollouts -n sharkpay get ledger --watch
kubectl argo rollouts -n sharkpay abort|promote|undo ledger

# Observability
curl -s localhost:9091/api/v1/query --get --data-urlencode 'query=<promQL>'
```

**Golden rules that apply to every runbook:**

1. Never fix a money discrepancy by editing rows — post a **compensation
   entry** (`ledger` is append-only; the app role has no UPDATE/DELETE).
2. Never retry an ambiguous provider debit — park it (`PROCESSING`) and page.
3. Manual compensation entries, suspense resolution and KYC downgrade
   require **4-eyes** (two humans) — RB-7 procedure.
4. Idempotency keys make every retry safe: when in doubt, retry the
   *documented* idempotent endpoint, never hand-craft SQL writes.
5. Take a snapshot of what you did (times, commands, ids) — the incident
   timeline becomes the post-mortem and the decision-log entry
   (SECURITY §7).

## 2. Alert anchors

| Alert | severity | Meaning | Full procedure |
|---|---|---|---|
| `MetricsAbsent` | critical† | a service stopped emitting metrics — service or pipeline dead | [`metricabsent`](#metricabsent) |
| `HighErrorRate5m` | critical | 5xx ratio > 5% for 5 m | [`higherrorrate5m`](#higherrorrate5m) |
| `P99LatencyBreach500ms` | critical | API p99 > 500 ms for 5 m | [`p99latencybreach500ms`](#p99latencybreach500ms) |
| `LedgerPostingStalled` | critical | zero successful postings 15 m while API traffic flows | [`ledgerpostingstalled`](#ledgerpostingstalled) |
| `WalletProjectionLag` | critical | projection lag > 5 s for 5 m | [`walletprojectionlag`](#walletprojectionlag) |
| `ProviderCircuitBreakerOpen` | warning | breaker open ≥ 2 m per provider | [`providercircuitbreakeropen`](#providercircuitbreakeropen) |
| `NATSConsumerLagHigh` | warning | consumer lag > 1000 msgs for 10 m | [`natsconsumerlaghigh`](#natsconsumerlaghigh) |
| `WebhookDeliveryFailureRate` | warning | failure ratio > 0.5% for 10 m | [`webhookdeliveryfailurerate`](#webhookdeliveryfailurerate) |
| `JvmMemoryPressure` | warning | heap > 85% for 10 m (`JvmMemoryCritical`: > 95% / 5 m) | [`jvmmemorypressure`](#jvmmemorypressure) |

† `fx`/`risk` alert at `warning` (they fail closed / degrade; the
money-movement services page). Routing: `page` → pager now; `critical` →
pager (30 s grouping); `warning` → ticket (5 m batch). Inhibition: critical
suppresses warning on the same `alertname+service`.

## metricabsent

**Fires when:** no metrics from a service for 10 m (`MetricsAbsent`,
per `service_name`). **Severity:** critical (warning for fx/risk).

1. Confirm the service is actually down vs. the pipeline is blind:
   `curl -s localhost:<port>/healthz` (Go) or `/actuator/health` (Java);
   k8s: `kubectl -n sharkpay get pods -l service=<name>`.
2. Check the telemetry pipeline before blaming the service:
   `curl -s localhost:9091/-/healthy` (Prometheus), collector health
   `curl -s localhost:13133` (dev overlay), and whether **other** services
   still emit. Only one service missing = service problem; all missing =
   `OtelCollectorDown` (S1, platform blind — page the observability owner).
3. Service down in k8s: `kubectl -n sharkpay describe pod <pod>` → crash
   loop? OOMKilled → `#jvmmemorypressure`; failing probe → check `/actuator/health` output
   for the failing dependency (DB → RB-8; Keycloak → RB-5).
4. Rollback-first: if a deploy preceded the absence, abort the canary
   (RB-6). Otherwise restart the workload
   (`kubectl -n sharkpay rollout restart deploy/<svc>`) and watch it come
   back on the platform-overview dashboard.
5. **Escalation:** money-path service (ledger/providers/wallet) still absent
   after 10 m of remediation → S1, incident commander per RB-9.

## higherrorrate5m

**Fires when:** 5xx ratio > 5% for 5 m. **Severity:** critical.

1. Scope it: `sum by (service_name)(rate(sharkpay_service:http_errors:rate5m))`
   — one service or many?
2. Grep the error signature in logs (Grafana → Loki, filter
   `service="<svc>"` and `level="ERROR"`); pull 3 example `trace_id`s and
   open them in Tempo.
3. Decision tree:
   - Errors are `jdbc`/connection/timeout → data layer → **RB-8**.
   - Errors are 401/403 storms (not 5xx, but user-visible auth failure) →
     Keycloak → **RB-5**.
   - Errors from providers adapters (`sharkpay_providers_transfers_total`
     failing) → rail down → **RB-2**.
   - Errors after a deploy → **RB-6** (canary should have caught it; if it
     rolled out fully anyway, abort/undo now).
4. If root cause is unclear and the error budget is fast-burning
   (`SLOAvailabilityFastBurn` co-firing), freeze deploys for that service
   (OBSERVABILITY §8) and treat as S2/S1 by blast radius.

## p99latencybreach500ms

**Fires when:** API p99 > 500 ms for 5 m. **Severity:** critical.

1. Identify slow service + endpoint: Tempo search for spans > 500 ms;
   `sharkpay_service:http_request_duration_seconds:p99_5m` per service.
2. Check the three usual suspects in order:
   - **DB saturation:** `sharkpay_ledger_lock_wait_seconds` p99 (ledger);
     HikariCP pending (`hikaricp_*`, JVM dashboard); long-running txns via
     `pg_stat_activity` — hot-account contention playbook in
     BACKEND-DESIGN §12 (shard `fees:*`/`clearing:*` if sustained).
   - **Downstream dependency:** slow provider rail → breaker metrics
     (`sharkpay_providers_breaker_state`), Keycloak JWKS refresh stalls →
     RB-5.
   - **Queue backpressure:** consumer lag (`#natsconsumerlaghigh`) causing synchronous
     compensating reads.
3. If a deploy introduced it → RB-6. If load-induced → verify HPA scaled
   (`kubectl -n sharkpay get hpa`); check PDB didn't block scale-out.
4. Sustained > 30 m with budget burn → S2; money-path p99 posting breach
   additionally pages via `LedgerPostingP99SLOBreach`.

## ledgerpostingstalled

**Fires when:** zero successful `sharkpay_ledger_postings_total{result=success}`
increments in 15 m while the ledger's HTTP traffic keeps flowing.
**Severity:** critical — this is "money path down".

1. Liveness split:
   `curl -s localhost:8090/healthz` (process up?) and
   `curl -s localhost:8090/readyz` (store reachable?) — `readyz` failing
   with API traffic = **DB problem → RB-8**.
2. If both green but postings fail: sample the error code label —
   `sum by (error_code)(rate(sharkpay_ledger_postings_total{result="error"}[5m]))`
   — `unbalanced_entry`/`wallet_negative` storms mean an upstream service
   is constructing invalid entries (contract violation, S2, capture the
   failing `transaction_key`s from logs and contact that service's owner).
3. If postings succeed but *nothing arrives* (counter zero, no errors):
   check who calls the ledger — payments workflows stuck → **RB-1**;
   NATS feed wedged → **RB-3**.
4. Never restart the ledger to "clear" it while transactions are in flight
   without checking `pg_stat_activity` for lock waits first — if restart is
   truly needed, it is stateless (all state in PG), so a rolling restart is
   safe *after* RB-8 confirms the database is healthy.
5. **Escalation:** S1 immediately (funds at risk / ledger integrity) —
   RB-9 matrix; consider gateway per-rail kill switch to stop new intent
   creation while diagnosing.

## walletprojectionlag

**Fires when:** `sharkpay_wallet_projection_lag_seconds` > 5 s for 5 m.
**Severity:** critical (balances are stale — reads lie).

1. Distinguish lag from **drift**: lag = consumer behind (events pending);
   drift = applied-but-wrong (balances ≠ ledger). Check `#natsconsumerlaghigh` first —
   if `NATSConsumerLagHigh` co-fires, run **RB-3** (feed problem). If lag
   is high with a healthy consumer, run the drift query in **RB-4** (Verify convergence).
2. Confirm the consumer is applying:
   `rate(sharkpay_wallet_ledger_events_applied_total[5m])` — result
   breakdown (success/duplicate/out_of_order/error). `error` > 0 → inspect
   one poison event (RB-3 §DLQ).
3. If wallet pods are CPU-saturated (JVM dashboard) → HPA should scale;
   verify, and check for a hot wallet (single principal×currency account
   with extreme event rate — legitimate, wait it out).
4. After the feed catches up, always run the RB-4 convergence query —
   a lag window that included failed events may have left gaps.

## providercircuitbreakeropen

**Fires when:** breaker open ≥ 2 m for a provider. **Severity:** warning
(by design — the breaker is the protection working).

1. Confirm which rail:
   `sharkpay_providers_breaker_state{provider="honeycoin",state="open"}`.
2. Check callback health — if callbacks are failing signature/replay
   (result labels on `sharkpay_providers_callbacks_total`) → **RB-2**
   (callback storm, not rail outage).
3. Genuine rail outage: verify with the provider status page and one manual
   probe (`curl` the HoneyCoin test endpoint from the wiremock/sandbox).
   The router already excluded the provider (hard filter); payments either
   route to an alternate rail or park — monitor
   `payments.payment.failed.v1` volume.
4. Do **not** force `breaker.Reset()` unless the provider has confirmed
   recovery — half-open probing does that automatically (60 s).
5. Single-provider at launch (HoneyCoin): sustained open > 30 m → consider
   the per-rail kill switch (gateway) to fail fast at intent creation, and
   post a status note to merchants (webhook `payment.failed` volume).

## natsconsumerlaghigh

**Fires when:** consumer lag > 1000 msgs for 10 m. **Severity:** warning.
Full procedure: **RB-3**.

Quick triage: `nats consumer info <stream> <consumer>` → `NumPending` vs
`NumAckPending`. Pending = consumer too slow (scale/fix consumer);
AckPending = redelivery loop (poison messages — check DLQ). Never delete a
consumer to "fix" lag — its durable position is what guarantees ordering.

## webhookdeliveryfailurerate

**Fires when:** `sharkpay:webhook_delivery_failure:ratio10m` > 0.5% for
10 m. **Severity:** warning.

1. Scope: per endpoint (api-gateway `webhook_deliveries` rows,
   `state`/`attempt`/`next_retry_at`) vs global. Global failure = our side
   (api-gateway saturation → HPA; egress blocked → NetworkPolicies
   `06-allow-egress-provider-apis`).
2. Per-endpoint failure = merchant endpoint down/unhealthy: retries are
   already running (exponential 1 m → 1 h, 8 attempts). Verify their
   server responds TLS-1.2+; check our signature window (± 5 min clock
   skew is the classic cause — `t=` timestamp vs their server time).
3. Endpoints going `dead` (8 attempts exhausted) need merchant contact +
   Console resurface — this is a support action, not an ops emergency.
4. If failures are 5xx **from our gateway** (not merchant), check the DLQ
   and the api-gateway logs; a signature-secret rotation bug shows as
   merchant-side 401s *after* our success — coordinate with the merchant.

## jvmmemorypressure

**Fires when:** heap > 85% for 10 m (`warning`) / > 95% for 5 m
(`JvmMemoryPressure` → `JvmMemoryCritical`). Affects Java services
(identity, wallet, fx, risk, later payments/payouts/api-gateway).

1. Identify the service and the shape: sawtooth that touches 95%+ with
   long GC pauses (`jvm_gc_pause` p99 on the JVM dashboard) = heap too
   small; monotonic climb = leak.
2. Short-term relief: `kubectl -n sharkpay rollout restart deploy/<svc>`
   (stateless — safe any time; in-flight HTTP requests drain per PDB).
   Prefer a rolling restart over OOMKill.
3. Correlate with load: projection catch-up (RB-3/RB-4 re-fold loads whole
   `PostingSequence` history into memory per wallet — a full re-fold job
   must be chunked); statement reads on wallets with huge history.
4. Resize: bump requests/limits per the runtime-label patch targets in
   `infrastructure/k8s/overlays/prod` (Java 500m/1Gi → 2/2Gi) — a kustomize
   change + normal rollout, not a hotfix.
5. Leak suspected: capture `kubectl exec <pod> -- jcmd 1 GC.heap_info` and
   open an S3 ticket with the heap histogram attached; do not iterate
   blindly in prod.

## 3. Incident runbooks (RB-1 … RB-8)

### RB-1 — Stuck payment (stuck in PROCESSING / no terminal state)

**Symptoms:** merchant reports a payment stuck (no `payment.succeeded`
webhook); `LedgerPostingStalled` or `TemporalWorkflowFailureRate` may
co-fire; support ticket references `pay_…` id.

**Diagnosis:**

```bash
# 1. Workflow state (payments run CreatePaymentWorkflow; Temporal query-by-id)
temporal --address <temporal:7233> workflow show --workflow-id 'payment:<intent_id>' \
  --output json | jq '.[] | {status: .closeEvent | type, history: [.events[].eventType]}'
# dev UI: http://localhost:8181 → namespace → workflow id search

# 2. Intent + full transition timeline (payments schema)
psql "$DB" -c "SET search_path TO payments; \
  SELECT id, state, provider_id, provider_ref, expires_at FROM payment_intents WHERE id='<intent_id>'; \
  SELECT to_state, trigger, actor, created_at FROM payment_state_transitions \
  WHERE intent_id='<intent_id>' ORDER BY id;"

# 3. Money state: which ledger entries exist for this intent? (source_ref)
psql "$DB" -c "SET search_path TO ledger; \
  SELECT id, transaction_key, entry_type, reverses_entry_id FROM journal_entries \
  WHERE source_ref='<intent_id>' ORDER BY created_at;"
# expect: hold entry at PENDING_PROVIDER; capture entry at SUCCEEDED

# 4. Wallet hold state
psql "$DB" -c "SET search_path TO wallet; \
  SELECT id, state, amount_minor, source_ref FROM holds WHERE source_ref='<intent_id>';"

# 5. Provider rail truth (adapter audit)
psql "$DB" -c "SET search_path TO providers; \
  SELECT * FROM adapter_calls WHERE source_ref='<intent_id>' ORDER BY created_at;"
```

**Decision tree:**

| Observation | Diagnosis | Action |
|---|---|---|
| Workflow `Running`, last event = provider activity scheduled + retries counting | rail slow/breaker open | `#providercircuitbreakeropen`; wait or park; **do not** retry the debit |
| Workflow `Running`, stuck on ledger activity with DB errors | ledger/DB degraded | RB-8, then resume workflow (`temporal workflow signal` / let retries continue — ledger posts are idempotent by `transaction_key`) |
| Provider confirms SUCCESS, workflow didn't progress | missed callback | poll result is authoritative; if the workflow polls, confirm timer; worst case restart the activity via a new workflow run with same id policy |
| Provider FAILED but hold still `active` | compensation not run | release path below |
| Funds captured (`capture` entry exists) but intent ≠ SUCCEEDED | transition write lost | re-drive the transition through the workflow/API, never SQL |
| Terminal state but merchant complains | webhook delivery | `#webhookdeliveryfailurerate`, re-deliver from `webhook_deliveries` |

**Remediation — compensating reversal path (money moves only here):**

1. Only after the provider outcome is **unambiguous** (failed/returned, or
   ops-confirmed double-capture): unwind is always compensation entries,
   never edits:
   `POST /internal/transactions/<entry_id>/reverse` with `reason` (required,
   ≤ 500 chars) and `operator_id`. Retry-safe: the reversal key is
   deterministic (`ops:rev:<entry_id>`) — a retry replays the same entry.
2. Release the hold before/with the reversal so `available` recovers
   (wallet internal endpoint with a fresh `Idempotency-Key`).
3. Partial refunds: post an adjustment/reversal for the sub-amount —
   `reversal amount ≤ captured amount` (STATE-MACHINES §1 guard).
4. **Never** reverse a reversal (ledger rejects with `reversal_of_reversal`
   — post an adjustment entry instead).

**Escalation:** S2 if one payment (support-driven); S1 if a workflow class
is stuck at scale (provider-wide) — incident commander, freeze new intents
via the gateway per-rail kill switch.

**Rollback:** compensating entry pairs (each reversal references its
original); if a compensation itself was wrong, another compensation — the
ledger remains balanced at every step.

### RB-2 — Provider callback storm (volume, forgeries, or replays)

**Symptoms:** `ProviderCircuitBreakerOpen` and/or
`sharkpay_providers_callbacks_total{result=~"signature_failure|replay|stale"}`
spiking; providers pod CPU/latency up; payment confirmations delayed
(downstream workflows waiting on callbacks).

**Diagnosis:**

```bash
# 1. Breaker + callback result breakdown
curl -s localhost:9091/api/v1/query --get \
  --data-urlencode 'query=sum by (result,provider)(rate(sharkpay_providers_callbacks_total[5m]))'
curl -s localhost:9091/api/v1/query --get \
  --data-urlencode 'query=sharkpay_providers_breaker_state'

# 2. Queue depth if the storm backs up JetStream consumers
nats consumer info <PROVIDER_EVENTS_STREAM> <consumer>

# 3. Log signatures (forged vs replayed vs stale)
# Loki: {service="sharkpay-providers"} |= "callback" | json | result="signature_failure"
```

**Decision tree:**

| Signature | Meaning | Action |
|---|---|---|
| `signature_failure` dominant | forgeries or a signing change on the provider | step 2 below — treat as security event (S1) |
| `replay` dominant (same `event.id`s) | provider (or attacker) re-sending; our replay cache is protecting | step 3 — check cache health, then provider contact |
| `stale` dominant | clock skew / delayed delivery beyond the ±5 min window | verify NTP on our side; ask provider about their queue |
| volume-only, all `success` | legitimate burst (settlement batch) | scale providers, confirm breaker stays closed |

**Remediation:**

1. **Stop the bleeding:** if callbacks are forgeries, the callback ingress
   is failing closed already (`verify.go` rejects before any state
   change). Optionally engage the per-rail kill switch at the gateway so
   new initiations stop while you investigate.
2. **Signature failures:** compare one raw callback's signature against the
   provider's documented scheme (HoneyCoin adapter in
   `services/providers/internal/honeycoin/signing.go`); if the provider
   rotated keys, update the vault secret (4-eyes, RB-7-style change record)
   and re-verify with the wiremock fixtures in `tests/wiremock/`.
3. **Replay cache purge (only if the cache itself is poisoned/wedged):**
   the cache is Redis 7, keys are cache-only (never truth). Purge narrowly:
   `redis-cli --scan --pattern 'callback:replay:*' | head` → inspect →
   delete the specific window. A purge widens the replay window briefly —
   do it in a maintenance moment, never mid-storm unless the cache is the
   fault (duplicates are still caught by `transaction_key`/event-id dedup
   downstream — §5 layers of BACKEND-DESIGN).
4. **Queue:** if JetStream consumer lag co-fires → RB-3.
5. Genuine rail outage masquerading as callbacks → `#providercircuitbreakeropen`.

**Escalation:** confirmed forged callbacks = S1 (security incident,
SECURITY §7 war-room; provider freeze authority). Provider-side incident:
S2, open a vendor ticket, park affected payments as `PROCESSING`.

**Rollback:** nothing to roll back — verifications are stateless rejects;
breakers self-heal (open 60 s → half-open). If a vault secret change was
wrong, restore previous secret (4-eyes) and re-run conformance fixtures.

### RB-3 — NATS JetStream consumer lag / DLQ drain

**Symptoms:** `NATSConsumerLagHigh` (> 1000 msgs / 10 m);
`WalletProjectionLag` co-firing for wallet consumers; consumers of record
(events.md table) behind.

**Diagnosis:**

```bash
# 1. Stream + consumer state
nats stream info LEDGER_POSTING          # msgs, first/last, consumers
nats consumer info LEDGER_POSTING wallet-projection \
  --json | jq '{num_pending, num_ack_pending, redelivered, deliver_policy}'

# 2. What is stuck? peek without consuming:
nats stream view LEDGER_POSTING --count 5 --json | jq '.[].subject'  # or payload
nats consumer next LEDGER_POSTING wallet-projection --count 1 --raw   # one manual pull

# 3. Consumer-side error signature
curl -s localhost:9091/api/v1/query --get \
  --data-urlencode 'query=sum by (result)(rate(sharkpay_wallet_ledger_events_applied_total[5m]))'
```

**Decision tree:**

| Observation | Cause | Action |
|---|---|---|
| `NumPending` high, `NumAckPending` ~0 | consumer too slow / down | scale the consumer service (HPA/manual); if the service is down → `#metricabsent` |
| `NumAckPending` high, `redelivered` climbing | messages failing processing → poison | drain DLQ (below) |
| Consumer crashed mid-batch, durable position intact | transient | restart; ordering per subject preserved by the durable consumer |
| Wallet applied-event rate = 0 but stream grows | consumer wedged (no acks, no errors) | restart pods; if persists, recreate consumer **with same durable name + deliver policy** (position survives) |

**Remediation:**

1. **Replay** (safe by construction — consumers are idempotent on `event.id`
   and wallet dedups on `posting_id`, BACKEND-DESIGN §5b):
   reset a consumer's position only deliberately:
   `nats consumer update LEDGER_POSTING wallet-projection --deliver-all` or
   recreate with `--deliver-subject` + same durable name. For a full
   projection rebuild from the ledger, prefer RB-4's re-fold (source of
   truth is the ledger, not the stream).
2. **DLQ drain (poison messages):**
   ```bash
   nats stream view <DLQ> --all --json > /tmp/dlq-dump.json
   jq '.[] | {id: .id, subject: .subject}' /tmp/dlq-dump.json
   ```
   Classify each: contract violation (fix producer, then replay via a new
   subject publish or `nats stream view` re-publish), consumer bug (fix,
   re-deliver), corrupt (quarantine file + S2 ticket). **Never auto-drop.**
3. Verify catch-up: lag gauge < SLO (5 s wallet), applied-rate matches
   stream rate, then run RB-4's verify-convergence query.

**Escalation:** lag > 10× SLO for 30 m or DLQ growing unbounded → S2.
Money-adjacent events in DLQ > 1 h → S2 with finance notified.

**Rollback:** consumer position changes are the "state" — if a replay
misbehaves, `nats consumer info` shows the current ack floor; you can
always re-replay from `--deliver-all` because idempotency makes re-delivery
convergent, and the ledger remains untouched throughout.

### RB-4 — Wallet projection drift (balances ≠ ledger)

**Symptoms:** recon break on wallet-vs-ledger totals; customer/ops balance
discrepancy report; `WalletProjectionLag` after a feed incident (`#walletprojectionlag`);
`ProjectionInconsistencyException` in wallet logs.

**Diagnosis — the drift query (detect):**

```sql
-- Ledger truth: per account, credits − debits
SET search_path TO ledger;
SELECT account_id, SUM(credit) - SUM(debit) AS total_minor
FROM postings GROUP BY account_id;

-- Wallet projection: latest wallet_postings running balance
SET search_path TO wallet;
SELECT wallet_id, balance_after AS total_minor
FROM wallet_postings wp
WHERE (wallet_id, posting_id) = (SELECT wallet_id, MAX(posting_id)
  FROM wallet_postings w2 WHERE w2.wallet_id = wp.wallet_id);

-- Join on wallet.ledger_account_id; any mismatch = drift.
-- Count of applied events vs ledger postings on the account:
SELECT count(*) FROM applied_ledger_events;  -- vs ledger posting count
```

(The `reconciliation` service automates this pairing daily —
`recon.break.detected.v1` — but the manual query above is the interim
procedure until phase 10.)

**Decision tree:**

| Finding | Cause | Action |
|---|---|---|
| Ledger ≠ projection, applied-event count < posting count | missed events (gap) | re-fold (below) |
| Counts equal, balances differ | wrong application (bug) | re-fold + fix the consumer bug first (else drift returns) |
| Ledger shows entry, wallet rejected the event (`result=error` in DLQ) | contract violation | fix producer, replay that event, then re-fold |
| Ledger itself looks wrong | **stop** — this is an S1 ledger-integrity incident (RB-9, SECURITY §7), not a projection issue |

**Remediation — re-fold from ledger events (the wallet is rebuildable by
design):**

1. The wallet's `PostingSequence` folds legs **in `posting_id` order** and
   dedupes on `posting_id` — re-applying the full history converges to the
   exact same projection (BACKEND-DESIGN §4). Execute:
   - preferred: replay the stream from the beginning for the affected
     wallets (RB-3, Remediation step 1: `--deliver-all`), or
   - targeted: re-POST missing events to the wallet feed
     (`POST /internal/ledger-events`, dev bootstrap path) — idempotent per
     `event.id` via `applied_ledger_events`.
2. Run per-wallet in bounded batches (memory: the fold is per-wallet, but
   don't heat the JVM → `#jvmmemorypressure`).
3. Holds are *not* part of the fold (they are wallet-owned state) — verify
   `available = total − held ≥ 0` after re-fold; a frozen wallet blocks new
   holds only, so in-flight hold capture is unaffected.

**Verify convergence:** re-run the drift query → zero mismatches;
`total ≥ 0` everywhere; the tail line of `wallet_postings` equals the ledger
`SUM(credit) − SUM(debit)` per account; spot-check one wallet's statement
against `GET /internal/accounts/{id}/statement` (ledger-owned, authoritative).

**Escalation:** drift affecting many wallets or any negative total → S1.
Single-wallet drift fixed by re-fold → S3 record + bug ticket for the root
cause.

**Rollback:** re-fold is convergent and ledger-side data is never touched —
"rollback" = re-running the fold from scratch. Never hand-edit
`wallet_postings` rows (append-only by design; PK `(wallet_id, posting_id)`
rejects duplicates anyway).

### RB-5 — Keycloak outage / token validation failure

**Symptoms:** 401/403 storm on user-facing endpoints (interfaces); Java
services logging JWKS fetch failures; `HighErrorRate5m` may co-fire;
Keycloak health check failing (dev: `curl -s localhost:8080/health`).

**Diagnosis:**

```bash
curl -s localhost:8080/realms/sharkpay/.well-known/openid-configuration | jq .jwks_uri
curl -s localhost:8080/realms/sharkpay/protocol/openid-connect/certs | jq 'length'   # keys present?
kubectl -n sharkpay get pods -l app=keycloak  # or the dev compose: docker compose ps keycloak
# Service-side: are tokens rejected because of missing JWKS, or bad tokens?
# Loki: {service=~"sharkpay-(wallet|identity|payments)"} |= "JWKS|issuer|invalid_token"
```

**Behavior during outage (by design — fail-closed vs fail-open per route
class):**

| Route class | Behavior when Keycloak is down | Rationale |
|---|---|---|
| Money mutations (payments create, payouts, transfers, wallet internal ops) | **fail closed** — reject new unauthenticated traffic | money must never move on stale identity assumptions |
| Authenticated reads (statements, balances) | fail closed after token TTL (typically minutes) | correctness; reads are cheap to retry |
| Already-validated sessions | continue while JWT valid — validation is **local** (cached JWKS, per-service Spring Security) | no per-request Keycloak hop exists by design (BACKEND-DESIGN §10) |
| Machine API keys (`sk_live_*`) at the gateway | unaffected — API keys are argon2id-hashed locally, not Keycloak tokens | platform keeps serving merchants |
| Webhook delivery, consumers, projection feed | unaffected | no user authn involved |

The cached-JWKS design means a full Keycloak outage degrades **new logins
and token refresh only** — in-flight API traffic and the whole async money
path continue.

**Remediation:**

1. Keycloak down: restart it
   (dev: `docker compose restart keycloak`; k8s: `kubectl -n sharkpay
   rollout restart deploy/keycloak`). Realm `sharkpay` re-imports from
   `infrastructure/dev/keycloak/sharkpay-realm.json` on fresh volume.
2. JWKS stale (rotation happened, services hold old keys): services refresh
   on `kid` miss automatically; force-refresh by bouncing the affected
   service only if logs show persistent `invalid_token` after Keycloak is
   healthy.
3. Clock skew (±30 s JWT `iat`/`exp` failures): fix NTP on hosts; tokens
   otherwise valid.
4. If the realm cert/signing key was rotated *by us*: 4-eyes secret change
   record, then update the realm + wait one refresh cycle before revoking
   the old key (24 h overlap discipline, same as API-key rotation).

**Escalation:** outage > 30 m during business hours → S2 (no funds at
risk; new-login blocker). Any suspicion of *compromise* (unknown keys in
JWKS, tokens we did not issue) → **S1 security incident**, revoke realm
keys, force re-authn.

**Rollback:** none needed beyond restart; if a config change caused it,
git-revert the configmap + Argo resync (RB-6, Rollback).

### RB-6 — Canary auto-rollback failed to trigger (bad deploy fully rolled)

**Symptoms:** SLO breach (`#higherrorrate5m`/`#p99latencybreach500ms`) after a deploy; the Argo Rollouts
canary **completed to 100%** despite metrics being bad (analysis didn't
fire, Prometheus unreachable, or thresholds misjudged).

**Diagnosis:**

```bash
kubectl argo rollouts -n sharkpay status ledger          # current phase
kubectl argo rollouts -n sharkpay get ledger --watch     # step history
kubectl -n sharkpay get analysistemplates -o name        # api-success-rate / api-p99-latency
kubectl -n sharkpay describe rollout ledger | tail -40   # analysis run results + measurement errors

# Did the analysis queries even return data?
curl -s localhost:9091/api/v1/query --get \
  --data-urlencode 'query=sharkpay_service:slo_availability:ratio1h{service_name="sharkpay-ledger"}'
```

**Decision tree:**

| Finding | Cause | Action |
|---|---|---|
| AnalysisRun `Error`/`Inconclusive` | Prometheus unreachable from the analysis job (NetworkPolicy `07-allow-from-monitoring`, metric name mismatch) | manual decision + fix the analysis wiring; below |
| Analysis `Successful` but SLO breached | thresholds/window too lax (e.g., metric absent → `inconclusiveLimit` swallowed) | abort + retune the AnalysisTemplate before next rollout |
| No AnalysisRun exists | rollout bypassed analysis (steps removed in dev overlay applied to prod by mistake) | abort + restore canary steps from base |
| Bad revision was a schema migration | rollback is NOT a plain undo (forward-only migrations) | see Rollback below |

**Remediation (manual promote/abort — the override path):**

1. **Abort the rollout** (traffic returns to the stable ReplicaSet at the
   previous revision, `maxUnavailable 0` so no capacity gap):
   `kubectl argo rollouts -n sharkpay abort ledger`
2. If already at 100% and stable: **undo** to the prior revision:
   `kubectl argo rollouts -n sharkpay undo ledger`
   (git path: `git revert` the tag bump + let Argo CD sync — the canonical
   prod flow in `infrastructure/k8s/README.md`).
3. **Promote (override) — only when a human has verified the new revision is
   actually good** (analysis false-negative):
   `kubectl argo rollouts -n sharkpay promote ledger --full`
4. Pause instead of deciding under pressure:
   `kubectl argo rollouts -n sharkpay pause ledger` → investigate → promote
   or abort.
5. Fix the analysis wiring *before* the next deploy: metric names must match
   the harmonized RED set (OBSERVABILITY §4.3); `MetricsAbsent` during a
   canary must read as failure (the k8s base sets `inconclusiveLimit 3` on
   purpose — tighten to 0 for the money-path Rollouts if this recurs).

**Escalation:** S2 (deploy tooling failure is systemic — every future deploy
inherits it). Ledger/providers/payments Rollouts: treat the fully-rolled bad
revision as S1 if posting/money path is impaired (`#ledgerpostingstalled`).

**Rollback:** `abort`/`undo` are instant for app code (images are
immutable tags per overlay). **Migrations are forward-only** — undoing code
that added a forward migration is safe *if* the migration was additive
(our standard: ledger migrations additionally require ops sign-off,
DATA-MODEL §5); a destructive migration requires a new forward migration to
restore shape, written under 4-eyes (RB-7).

### RB-7 — Reconciliation break escalation (4-eyes compensation)

**Symptoms:** `recon.break.detected.v1` events; breaks aging in the recon
console; daily recon report non-zero; SECURITY §6 detection alert on breaks
aging > 24 h.

**Diagnosis (aging first):**

```sql
SET search_path TO reconciliation;
SELECT state, count(*), min(created_at) AS oldest, now() - min(created_at) AS age
FROM breaks GROUP BY state ORDER BY age DESC;
-- per break: expected vs actual legs (ledger side vs provider statement side)
SELECT * FROM breaks WHERE id = '<break_id>';
-- supporting: ledger entries for the break's source_ref (RB-1, Diagnosis step 3) and
-- the provider's adapter_calls / statement lines for the same window.
```

**Aging buckets & required posture:**

| Age | Classification | Required action |
|---|---|---|
| 0–24 h | fresh | auto-recon retry (timing skew is common: settlement files arrive late); owner: finance-ops, ticket |
| 24–72 h | aging | **page** (SECURITY §6 alert); named owner; hypothesis written in the ticket (timing / amount / missing leg / provider error) |
| > 72 h | escalated | S2 minimum; if any suspicion of funds loss or ledger tampering → **S1** (funds at risk / ledger integrity) |

**Remediation — 4-eyes compensation entry procedure:**

1. **Hypothesis:** classify the break (expected≠actual legs, amount
   mismatch, duplicate provider line, missing provider line). Attach the
   ledger-side query and provider statement excerpt to the ticket.
2. **Draft the compensation** (operator A): the entry that makes both sides
   agree — typically legs involving `suspense:recon:KES` (ops-owned
   unresolved) or `honeycoin:settlement:KES` (settlement variance), with
   `entry_type=adjustment`, a `reason` quoting the break id, and
   `operator_id` = operator A.
3. **Second pair of eyes (operator B)** reviews the exact legs *before*
   posting — 4-eyes is mandatory for manual compensation entries, suspense
   resolution, and provider credential changes (SECURITY §4). Record B's
   approval in the ticket.
4. **Post via the API, never SQL:**
   `POST /internal/transactions` (source `ops`, key `ops:adj:<break_id>`) —
   the idempotency key makes a retry safe; the triggers re-assert balance
   and non-negativity (BACKEND-DESIGN §8).
5. **Resolve the break** in the console with the compensation entry id
   linked; the break row's resolution is the audit link.
6. If the break reveals a **bug** (systematic), file the producer/consumer
   fix and schedule a game-day check (SECURITY §7 quarterly rehearsal).

**Escalation:** breaks involving `wallet` accounts (customer money) or
unexplained ledger rows → S1 war-room. Break volume suddenly > 5× daily
norm → suspect provider statement format change or feed outage (RB-3).

**Rollback:** a wrong compensation is corrected by *another* compensation
entry referencing it (reversal-of-reversal is forbidden — use adjustment).
The decision log (PRD §15 / DATA-MODEL §5) gets an entry either way.

### RB-8 — Postgres failover (leader loss)

**Symptoms:** ledger `readyz` failing (store unreachable) with `healthz`
green; connection timeouts/`server closed the connection` across services;
`LedgerPostingStalled` (`#ledgerpostingstalled`); JDBC `CannotGetJdbcConnectionException` /
pgx `conn busy` in logs; PgBouncer saturated with waiting clients.

**App behavior during leader loss (by design):**

- Every write path is **idempotent** (BACKEND-DESIGN §5) — services fail
  the request or park the activity; Temporal retries activities; JetStream
  redelivers. **No money operation is half-applied**: a posting either
  committed (triggers passed, `transaction_key` consumed) or did not, and a
  retry converges to the same entry.
- Read paths fail fast; the ledger refuses to serve statements rather than
  serve from a stale source (`readyz` = store reachability).

**Diagnosis:**

```bash
kubectl -n sharkpay exec <postgres-pod> -- pg_isready -U sharkpay
# replication state (primary): who is sync standby, how far behind?
kubectl -n <postgres-ns> exec <primary> -- psql -U postgres -c \
  "SELECT application_name, sync_state, replay_lag FROM pg_stat_replication;"
docker compose ps postgres   # dev
```

**Decision tree:**

| Finding | Action |
|---|---|
| Primary alive, network partition to app tier | fix network/NetPol; services self-heal via retries — no failover |
| Primary dead, sync replica (RPO=0) caught up | promote replica (HA manager normally does this; verify it did) |
| Primary dead, sync replica lagging | **RPO at risk — S1**: promote only after accepting the data-loss window; finance + incident commander must be in the loop BEFORE promotion |

**Remediation / verification sequence (after promotion or primary return):**

1. Verify the new primary accepts writes:
   `psql -c "SET search_path TO ledger; INSERT ... (smoke txn via /readyz + one test posting in dev only)"`
2. Verify schema integrity: roles/grants intact
   (`SELECT grantee, privilege_type FROM information_schema.role_table_grants WHERE table_name='postings';`
   — `sharkpay_app` must have INSERT/SELECT **only**), triggers present
   (`pg_trigger` rows for `trg_entry_balanced`, `trg_wallet_non_negative`).
3. Point services at the new leader (DNS/HA proxy should make this a
   no-op; if manual, update the DSN secret and roll services
   **one at a time** — `kubectl rollout restart deploy/`).
4. Watch recovery: `sharkpay_ledger_postings_total` resumes,
   `LedgerPostingStalled` clears; JetStream consumers drain the backlog
   (RB-3 if lag persists).
5. **Post-failover reconciliation (mandatory within the hour):** run the
   RB-4 drift query across wallets + the RB-7 recon pairing — a failover is
   precisely when an at-least-once producer could have double-emitted
   (outbox rows committed, relay interrupted): consumer dedup absorbs it,
   *verify anyway*.
6. If PITR was needed (data loss window): restore to the last consistent
   minute into a side instance, extract the delta, and replay through the
   ledger API (idempotent keys make replay converge) under 4-eyes.

**Escalation:** any failover = S2 minimum, incident channel opened; any
RPO>0 event = **S1** (ledger durability, NFR-04: zero tolerated entry loss,
RTO ≤ 15 min).

**Rollback:** re-promote the old primary **only** as a fresh replica
(rejoin via replication) — never as leader without re-sync; treat its
pre-failover divergent writes (if any) as recon breaks (RB-7).

## 4. RB-9 — On-call escalation matrix & severity levels

**Severity ladder** (SECURITY §7):

| Level | Definition | Examples | Response | Comms |
|---|---|---|---|---|
| **S1** | Funds at risk, ledger integrity, or total money-path outage | ledger stall, forged callbacks, RPO>0 failover, systematic recon breaks on wallet accounts | page immediately, war-room, incident commander appointed | status page + owner ping; post-mortem ≤ 5 business days with decision-log entry |
| **S2** | Money path degraded or systemic tooling failure | one rail down (breaker open > 30 m), stuck payment class, canary machinery broken, Keycloak outage | ack ≤ 15 min during cover, fix in-shift | incident channel, ticket |
| **S3** | Feature degraded, money unaffected | webhook endpoint failures, projection lag recovering, DLQ with non-money events | next business day | ticket |
| **S4** | Cosmetic / noise | dashboard panel wrong, flaky warning alert | backlog | ticket |

**Escalation matrix:**

| Ring | Who | When | How |
|---|---|---|---|
| 1 | Primary on-call (backend) | every page/critical alert (`severity=page` group_wait 0s; `critical` 30s) | Alertmanager pager (receiver URLs wired at deploy, OBSERVABILITY §9) |
| 2 | Secondary on-call (Go money-path / Java platform split by service) | no ack in 10 min (S1: 5 min) | pager escalation chain |
| 3 | Incident commander (rota; S1 only) | immediately on S1 declaration | war-room channel; has provider-freeze + gateway kill-switch authority |
| 4 | Owner @Roy-Wanyoike | any S1; S2 > 2 h | direct message + status page update |
| 5 | Provider (HoneyCoin) escalation | rail-confirmed incidents | vendor escalation contact; 4-eyes on any credential change |
| 6 | Observability platform owner | `OtelCollectorDown`, `AlertmanagerDown`, stack self-monitoring alerts | #observability (continue:true also pages on infra criticals) |

**Page discipline:** one incident = one page (Alertmanager inhibition:
critical ⊣ warning on `alertname+service`). Repeats: page 1 h, critical 2 h,
warning (ticket) 12 h. Every fired alert that reaches a human must end in
one of: runbook executed, runbook gap filed (§2 anchors are the contract),
or alert tuned — the quarterly game-day (SECURITY §7) rehearses at minimum:
provider outage (RB-2/`#providercircuitbreakeropen`), ledger failover (RB-8), projection re-fold
(RB-4), and canary abort (RB-6).
