# SharkPay Core — State Machines

| | |
|---|---|
| **Companion to** | [PRD](PRD.md) · [API Contracts](API-CONTRACTS.md) · [Data Model](DATA-MODEL.md) |
| **Rule** | Any transition not listed here is a bug. Transitions are persisted in `*_state_transitions`. |

---

## 1. Payment intent

```
CREATED ──risk hold ok──► PENDING_PROVIDER ──provider accepted──► PROCESSING
   │                          │        │                            │
   │ risk deny                │ expiry │ provider reject            │ rail confirms
   ▼                          ▼        ▼                            ▼
BLOCKED                    EXPIRED   FAILED                    SUCCEEDED
                                        │                            │
                                        │ ops/provider reversal      │ reversal
                                        ▼                            ▼
                                   REVERSED(same)              REVERSED
CREATED ──user cancel──► CANCELLED
```

| From | To | Trigger | Actor | Side effects (ledger/wallet) |
|---|---|---|---|---|
| CREATED | PENDING_PROVIDER | risk pass + hold placed | system | `hold` entry posted |
| CREATED | BLOCKED | risk deny | risk | no money moved |
| CREATED | CANCELLED | user/API | principal | none |
| PENDING_PROVIDER | PROCESSING | provider accepted | provider | — |
| PENDING_PROVIDER | FAILED | provider reject / hard error | provider | `release` entry |
| PENDING_PROVIDER | CANCELLED | user/API cancel (payments.yaml cancelPayment) | principal | `release` entry |
| PENDING_PROVIDER | EXPIRED | TTL | system | `release` entry |
| PROCESSING | SUCCEEDED | rail confirmation | provider | `capture` entry (hold → settled) |
| PROCESSING | FAILED | rail failure confirmed | provider | `release` entry |
| SUCCEEDED | REVERSED | reversal (full/partial) | provider/ops | `reversal` compensation entry |
| FAILED | REVERSED | late funds recovered | ops | compensation pair |

**Guards:** expiry only from `PENDING_PROVIDER`; `SUCCEEDED` is reachable only after
risk post-evaluation passed; reversal amount ≤ captured amount; all terminal states emit
webhook `payment.*`.

## 2. Payout

```
CREATED → PENDING_RISK → PROCESSING → SENT → SUCCEEDED
                │            │           │
                ▼            ▼           ▼
            BLOCKED       FAILED     RETURNED
```

Notable: `SENT` means accepted by rail, `SUCCEEDED` means settled at destination
(confirmed by callback/reconciliation). `RETURNED` posts a compensation entry and
re-credits the wallet (minus non-refundable rail fees where applicable).

## 3. Transfer (internal)

`CREATED → SUCCEEDED` (single atomic ledger transaction) or
`CREATED → FAILED` (pre-flight rejection — never partially posted).

## 4. FX conversion

`QUOTED → LOCKED → EXECUTED | EXPIRED` — lock consumes quote TTL; execution posts the
4-leg entry atomically; expiry of a locked quote is a p1 incident (ops console alert).

## 5. Wallet & KYC states

- Wallet: `active ⇄ frozen` (freeze by compliance only), `active → closed`
  (zero balances only). No delete.
- KYC tier: `unverified → limited → full` (upgrades only; downgrade to `unverified`
  requires case + 4-eyes).

## 6. Agent policy lifecycle

`draft → active ⇄ paused → revoked` — paused blocks new money movement, not in-flight
settlement; revocation requires owner confirmation webhook event.

## 7. Invariants across all machines

1. **Monotonicity:** state transitions only move forward except explicit reversal edges.
2. **Terminal webhooks:** every terminal state emits exactly one terminal webhook
   (at-least-once delivery).
3. **Replayability:** transitions + Temporal history + adapter_calls reconstruct any
   payment's full timeline (used by reconciliation and support).
4. **Money state alignment:** for any intent, ledger `hold/release/capture/reversal`
   entries exist iff the corresponding transitions occurred — recon validates this
   pairing daily.
