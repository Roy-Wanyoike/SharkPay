# SharkPay API Gateway (WP-9)

The public front door of SharkPay: **API-key authentication**, the
**webhook dispatcher**, the **/v1 passthrough skeleton** and the
**/sandbox simulated provider**. Java 25 + Spring Boot 4.1.1, hexagonal
architecture per [ADR 003](../../docs/adr/003-safe-parallelism-and-verification.md):
the domain and use-cases depend only on ports; every production adapter
that needs a live environment (JPA, real HTTP routing, the NATS/Kafka
event binding) is either component-scanned storage code or a fail-fast
integration placeholder, and local tests run the entire hexagon on
in-tree fakes — no Spring context, no database, no network (except one
loopback `com.sun.net.httpserver` server that exercises the real webhook
wire adapter).

The gateway moves no money itself: passthrough payloads are opaque JSON
relayed to the owning internal service.

## Surface

| Surface | Route class | Required scope | Backing |
|---|---|---|---|
| `POST/GET /v1/api-keys`, `POST /v1/api-keys/{id}/rotate|revoke` | `API_KEYS` | `apikeys:manage` | management API |
| `POST/GET/DELETE /v1/webhook-endpoints…` | `WEBHOOKS` | `webhooks:manage` | [webhooks.yaml](../../contracts/openapi/v1/webhooks.yaml) |
| `/v1/payments/**`, `/v1/payouts/**`, `/v1/transfers/**`, `/v1/wallets/**`, `/v1/fx/**` | per route | `*:read` on GET, `*:write` otherwise (`transfers` write-only, `wallets` read-only) | passthrough → `UpstreamPort` |
| `POST/GET /sandbox/payments…` | `SANDBOX` | `payments:write` / `payments:read` | in-memory simulated provider |
| `POST /internal/events` | — (service JWT, private surface) | — | dev event intake → dispatcher |
| `/actuator/health/**` | — | — | Kubernetes probes |

Unknown paths resolve to route class `UNKNOWN` → **403** (fail-closed:
no path is reachable without a declared route class and its scope).

## Auth model (docs/SECURITY.md §2, BACKEND-DESIGN.md §10)

* **API keys** are `sp_live_<43 base62>` (51 chars, ~256 bits) minted by the
  `Randomness` port (`SecureRandom` in production). **Only the SHA-256 hex
  hash is persisted** (fixed 64 lowercase-hex column, `V1` CHECK); the
  plaintext is returned **exactly once** in the creation/rotation response —
  never on idempotent replays, listings or logs. Revoke is immediate.
* **Rotation**: `POST /v1/api-keys/{id}/rotate` demotes the current secret
  to `ROTATING` with a **24 h grace window** — the old secret keeps
  authenticating until `grace_expires_at`, then fails exactly like an
  unknown key (401). The new secret inherits scopes and quotas.
* **Authentication** (`ApiKeyAuthFilter`): `Authorization: Bearer sk_…` →
  SHA-256 → repository lookup by hash → **constant-time compare**
  (`MessageDigest.isEqual` over the digests — no early-exit comparison on
  secret-derived values). 401 for missing/unknown/revoked/grace-expired.
* **Authorization** is fail-closed per route class: the required scope
  comes from the route table (GET→`payments:read`, POST→`payments:write`, …).
  A scope outside the fixed catalog can never be granted (`Scope.parse`
  rejects unknown names); a route class without a satisfiable scope for the
  method (e.g. `POST /v1/wallets`) is a 403.
* **Quotas**: per-key rpm (minute window) + monthly (UTC calendar month),
  check-and-consume on every authenticated request. 429 `quota_exceeded`
  with a `Retry-After` header (whole seconds until the window resets,
  minimum 1). Nothing is consumed on a rejection.
* **Bootstrap note**: the first key must be provisioned out-of-band (seed
  SQL / identity service integration) because creating keys requires a key
  — flagged for the integrator.
* The private surface (`/internal/**`) is a Spring Security resource server
  (Keycloak service JWT), exactly like the other internal services.

## Webhooks: signing, retries, DLQ

Outbound deliveries are `POST` with a CloudEvents 1.0-aligned JSON body
(unversioned catalog `type`, e.g. `payment.succeeded` — internal topic
versioning never leaks, per [events.md](../../contracts/events/events.md))
and three headers:

```
X-SharkPay-Signature: t=<unix seconds>,v1=<hex hmac-sha256(t + "." + raw_body, secret)>
X-SharkPay-Timestamp: <unix seconds>
X-SharkPay-Delivery:  <delivery id whd_...>
```

**Verify before trusting.** Any 2xx counts as delivered (body ignored).
Receivers must reject timestamps outside **±5 minutes** and **dedupe on
`event.id` / `X-SharkPay-Delivery`** — delivery is **at-least-once**, state
fields are monotonic.

### Curl verification example

```bash
# 1. capture the raw body and the signature headers of one delivery
read -r body < delivery.json
t=1767312000    # from X-SharkPay-Timestamp
v1=6b1f9c...    # from X-SharkPay-Signature

# 2. recompute the signature over t + "." + raw body with your secret
expected=$(printf '%s.%s' "$t" "$(cat delivery.json)" \
  | openssl dgst -sha256 -hmac "$WHSEC" -hex | sed 's/^.* //')

# 3. compare (constant-time on the receiver side is recommended)
[ "$v1" = "$expected" ] && echo "signature OK" || echo "FORGED"
```

### Retry / dead-letter policy

* Retry schedule **1 m, 2 m, 4 m, 8 m, 16 m, 32 m, 1 h (capped)** —
  `BackoffPolicy`, strictly monotonic up to the cap.
* **Exactly 8 attempts**; the 8th failure marks the delivery `dead`
  (`webhook_deliveries.state = dead`) — there is never a 9th send.
* After **3 consecutive dead deliveries** the subscription **auto-pauses**
  (state `dead`) and stops receiving new events; an operator resumes it via
  `POST /v1/webhook-endpoints/{id}/resume`, which also resets the counter.
* **Delivery idempotency**: the `(subscription, event id)` pair is unique —
  the dispatcher never creates a second delivery for an event an endpoint
  already holds, and a `delivered` row is never re-sent. The operator
  **replay** endpoint re-queues **only dead** deliveries
  (`POST /v1/webhook-endpoints/{id}/deliveries/{deliveryId}/replay`, 409
  for pending/delivered); the attempt counter restarts.
* Endpoint URLs are **https-only** (422 `http_url_required`).
* The delivery worker sweeps due deliveries every 30 s
  (`gateway.webhook.sweep-ms`); each send uses the JDK HttpClient with
  5 s connect / 10 s request timeouts and no redirects.

## /v1 passthrough + idempotency

Authenticated `/v1/**` requests are forwarded through the `UpstreamPort`
with the caller's principal id (never the API key) propagated. POSTs with
an `Idempotency-Key` are cached **per route class** (scope = key + route);
replays return the stored upstream response with
`X-Idempotent-Replay: true`, a different payload under the same key is a
409 `idempotency_conflict`, and 5xx responses are **not** cached (they are
"safe to retry" per common.yaml). Production wiring is a fail-fast
placeholder until the real HTTP routing adapter lands (integration phase).
Upstream base URLs (env, documented for the integrator):
`API_GATEWAY_UPSTREAM_{PAYMENTS,PAYOUTS,TRANSFERS,WALLETS,FX}`.

## Sandbox simulated provider

`/sandbox/payments` is a **clearly separated** simulated provider: in-memory
only (nothing persisted, nothing reconciled), deterministic scripted flow
`CREATED → PENDING_PROVIDER → SUCCEEDED` — each `GET` advances exactly one
step and dispatches the matching webhook event, so merchants can exercise
their signed receivers end-to-end with zero money movement.

## Storage

JPA + Flyway `V1__api_gateway_init.sql`: `api_keys`,
`webhook_subscriptions`, `webhook_deliveries` (unique
`(subscription_id, event_id)`), `quota_buckets`, `idempotency_cache` — with
CHECK constraints encoding the security invariants (64-hex hash, https URLs,
bounded attempts, window shapes).

## Configuration (env names `API_GATEWAY_*`)

| Env | Default | Meaning |
|---|---|---|
| `API_GATEWAY_DB_URL` | `jdbc:postgresql://localhost:5432/sharkpay_api_gateway` | datasource |
| `API_GATEWAY_DB_USER` / `API_GATEWAY_DB_PASSWORD` | `sharkpay_api_gateway` | datasource credentials |
| `API_GATEWAY_ISSUER_URI` | `http://localhost:8080/realms/sharkpay` | Keycloak issuer (private surface) |
| `API_GATEWAY_UPSTREAM_*` | internal service hosts | routing adapter (integration phase) |

Server port **8088**. See `src/main/resources/application.yml`.

## Build & test

```
cd services/api-gateway && mvn -B -ntp clean verify
```

Plain JUnit 5 + standalone MockMvc (Jackson 3 `tools.jackson` only) with the
`ApiKeyAuthFilter` attached — the front door behaves exactly like
production. Port fakes live in `src/test/.../fakes`; the fake event feed is
the executable spec of what the real NATS/Kafka binding must push.
