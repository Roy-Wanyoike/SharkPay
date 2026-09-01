# SharkPay Payments Service (WP-5)

Payment intent orchestration: create intents, run the risk → hold → route →
initiate → confirm → capture saga, compensate on every failure path, and drive
the payment state machine of `docs/STATE-MACHINES.md` §1 exactly.

| | |
|---|---|
| **Language / framework** | Java 25, Spring Boot 4.1.1 (Jackson 3 — `tools.jackson` only) |
| **Port** | `8085` (container) — host `8086` reserved in `docs/DEV-STACK.md` |
| **Orchestration** | Temporal (Java SDK) — task queue `payments`, guarded by `temporal.enabled` |
| **Money** | `com.sharkpay:sharkpay-money` — integer minor units, exact bps math, no floats |
| **Storage** | PostgreSQL via JPA + Flyway `V1__payments_init.sql` |
| **REST surface** | `contracts/openapi/v1/payments.yaml` (read-only, binding) |
| **Events** | `contracts/events/payments.payment.v1.json` (CloudEvents 1.0, UUID v7 ids) |

## Architecture (hexagonal, ADR 003)

```
api/            REST adapters: PaymentController (payments.yaml), InternalLifecycleController,
                GlobalExceptionHandler (400/404/409/422/500 error semantics)
domain/         PaymentIntent aggregate + state machine, FeePolicy/FeeSchedules (exact bps),
                RouterPolicy (pure routing function), Destination, exceptions
service/        Use-cases: Create, EvaluateRisk, PlaceHold, ProviderHandoff, Fail, Cancel,
                Expire, RecordProviderResult, Reverse, Get, List
ports/          RiskPort, WalletHoldPort, LedgerPort, ProviderGatewayPort, EventPublisher,
                IdempotencyStore, PaymentRepository, PaymentLifecyclePort, PrincipalResolver,
                Randomness (Clock is java.time.Clock, injected directly)
storage/        JPA adapters: payment_intents + payment_state_transitions (append-only)
                + idempotency_keys
workflow/       PaymentWorkflow(+Impl) saga, PaymentActivities(+Impl), TemporalWorkerManager
config/         PaymentsConfig (wiring), SecurityConfig (JWT resource server),
                TemporalWorkerConfig (guarded worker), fail-fast IntegrationPending* placeholders
events/         CloudEvent envelope + PaymentEvents factories (six catalog types)
```

Cross-service adapters are **fail-fast placeholders** (`config/IntegrationPending*`):
until the integrator wires the real risk/wallet/ledger/provider-gateway REST
clients, any money-moving call refuses loudly rather than pretending. Local
tests never need those services: the same hexagon is re-assembled on in-tree
fakes in `src/test` (ADR 003 §3).

## Payment saga (Temporal)

`PaymentWorkflow.orchestrate(paymentId)` drives the intent to a terminal state:

```
risk ──► hold ──► route+initiate ──► poll (until expiry deadline) ──► confirm ──► capture
  │         │            │                                             │
  │ deny    │            └─ reject / retries exhausted ─┐             └─ post-risk deny
  ▼         ▼                                            ▼                  ▼
BLOCKED   (hold placed)                        failPayment: release hold → FAILED
                                                        ▲
             expiry timer ──► expirePayment: release hold → EXPIRED
```

Rules honoured:

- **Compensation is ALWAYS release/reversal** (never an in-place mutation of a
  posted entry) — `docs/BACKEND-DESIGN.md` §6.
- **No money arithmetic in workflow code** — the workflow only sequences
  activities, compares strings/booleans and sleeps; all money math lives in
  activities/use-cases.
- **Every activity is idempotent** — the synchronous REST creation prefix may
  already have advanced the intent (payments.yaml: creation runs risk → hold →
  hand-off synchronously), and Temporal delivers activities at-least-once.
- **Provider polling** uses a retryable poll activity (3 attempts/cycle) and
  keeps resolving until the expiry deadline; `UNKNOWN` status parks (the
  provider.go AMBIGUITY CONTRACT — never guess whether money moved).
- The worker (`TemporalWorkerManager`, task queue `payments`) is created only
  when `temporal.enabled=true`; tests use `TestWorkflowEnvironment` (no server).

## Router policy (deterministic, heavily tested)

`domain/RouterPolicy.rank()` picks the provider for an intent in two stages.

**Stage 1 — hard filters (fail closed).** A candidate failing any filter is
never eligible, and an empty eligible set fails the payment (no invented
providers):

1. **currency capability** — the provider settles the payment currency;
2. **rail capability** — the provider serves the payment's rail;
3. **region** — the provider serves the payer's region or advertises `GLOBAL`;
4. **circuit breaker** — candidates with an OPEN breaker are excluded outright;
5. **KYC tier gate** — principal tier rank ≥ candidate minimum (unknown tier
   ranks 0 — fail closed);
6. **tier limits** — amount within the candidate's `[minTxn, maxTxn]` band.

**Stage 2 — deterministic integer scoring (lower is better).**

```
score = 5·costNorm + 3·latencyNorm + 2·failureRate        (all × 10 000)
costNorm     = costBps    × 10 000 / max(costBps    over eligible)
latencyNorm  = p99Millis  × 10 000 / max(p99Millis  over eligible)
failureRate  = 10 000 − successRateBps
```

The weights (5/3/2 = ×10 of 0.5/0.3/0.2) mirror the Go gateway's documented
defaults — cost drives unit economics, latency is second, health tertiary —
scaled into exact `long` arithmetic so every environment computes the
identical score (no binary-fraction surprises; routing is replayable, which
audits and payment reconstruction require). **Ties break by provider id
ascending**, so the same inputs always yield the same ranking.

Fail-over: `rank()` returns the full ordered list; the head is `select()`'s
choice and the tail is the deterministic fail-over ladder.

## Fees

`FeePolicy` computes `fee = bps·amount/10 000 + fixed`, clamped to
`[minimum, maximum]`, entirely in integer minor units: the bps share is
extracted with the money library's largest-remainder `allocate`, so the split
is lossless (the at-most-one leftover minor unit goes to the larger fractional
remainder; ties favour the fee part). Overflow of the sum is rejected
(`MoneyOverflowException` → 422 `money_overflow`), never wrapped. Schedules per
rail/currency live in `FeeSchedules` (V1 product defaults).

## Idempotency (ADR 003 G2)

Every mutating operation (create, cancel, provider-result, reverse) keys an
`idempotency_keys` row scoped by operation type: a replay with the same
fingerprint returns the original intent (no second effect), a different payload
is a 409 `idempotency_conflict`. The wallet hold port and ledger port are keyed
by the payment's internal UUID so at-least-once activity delivery cannot double
place/release/capture. A risk REVIEW rejection deliberately does **not**
consume the create key (nothing was persisted; the caller retries after the
review clears).

## Verification

```
cd services/payments && mvn -B -ntp clean verify
```

No database, no Temporal server, no Spring context: domain/service/API tests
run on in-tree fakes with standalone MockMvc; workflow tests run on Temporal's
`TestWorkflowEnvironment` with deterministic clocks. JaCoCo enforces
BUNDLE LINE ≥ 0.80 (target 85%).
