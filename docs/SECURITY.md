# SharkPay Core — Security Model

| | |
|---|---|
| **Companion to** | [PRD](PRD.md) · [Architecture](ARCHITECTURE.md) · [API Contracts](API-CONTRACTS.md) |

---

## 1. Threat model (STRIDE summary)

| Threat | Vector | Control |
|---|---|---|
| Spoofing | Stolen API key, forged provider callback | Key hashing + rotation; provider callback signature + timestamp + replay cache |
| Tampering | Ledger row modification | Append-only DB grants, no app role UPDATE/DELETE; compensation-only corrections |
| Repudiation | "I never initiated that" | Full audit trail: transitions, adapter_calls, request_logs with actor + trace |
| Info disclosure | PII/KYC leakage, logs | Field-level encryption for KYC docs; structured log redaction; vault-only secrets |
| DoS | API flood, webhook storms | Per-key quotas, rate limits, circuit breakers, queue-based webhook dispatch |
| Elevation | Agent exceeds mandate | Policy engine bound at key issuance AND re-evaluated per transaction |

## 2. AuthN / AuthZ

- **Users (interfaces):** MFA-capable sessions (identity service); device registry;
  step-up auth for payout creation above threshold.
- **API keys:** stored hashed (argon2id); prefix format `sk_live_` / `sk_test_` for
  identification; rotation with 24 h overlap window; scopes per §5 of API contracts.
- **Agents:** key issued against a **policy document** —
  `{ scopes, per_tx_limit, daily_limit, velocity, allowed_rails, destinations,
  expiry, requires_owner_webhook }` — enforced at gateway (scopes/quota) **and** at
  payments/payouts pre-authorization (limits/destinations). Both checks fail closed.
- **Internal:** mTLS between services; service identities in SPIFFE-style SANs.

## 3. Secrets & cryptography

- AWS Secrets Manager; per-provider credentials; 90-day rotation reminders.
- TLS 1.2+ everywhere (1.3 preferred); HSTS on console/web.
- At rest: RDS encryption; KYC documents in encrypted object storage with per-object KMS
  keys; envelope encryption for PII columns.
- HMAC-SHA256 for webhook signatures; Ed25519 for provider callback signing (HoneyCoin
  adapter negotiates provider scheme, platform-internal is always Ed25519).

## 4. Money-movement safety

- Hold → initiate → capture ordering; never capture without hold.
- 4-eyes approval for: manual compensation entries, suspense resolution, KYC downgrade,
  provider credential changes in prod.
- Circuit breakers per provider; automatic fail-safe state (`PROCESSING` parked with ops
  alert) on ambiguous provider responses — **never auto-retry ambiguous debits**.
- Idempotency keys enforced at API, service, ledger, and adapter layers (each has its
  own scope, all derive from the original request key chain).

## 5. Compliance controls

- KYC tier gating capability matrix (e.g., payouts require `limited+`; large limits
  require `full`).
- AML: transaction monitoring rules; case management; SAR export job (CSV/JSON package
  with full timeline per case).
- Data retention: financial records ≥ 7 years (immutable); PII deletions honor
  regulatory carve-outs (KYC data retained per law, marketing data purged).
- PCI-DSS scope minimized: no PAN storage in V1-V3; card data (V2/V3 expansion) enters
  only via tokenized provider fields.

## 6. Audit & detection

- Every financial write: actor, action, before/after state, reason, trace_id.
- Immutable audit log export to WORM S3 bucket daily.
- Detection: alerting on — manual adjustment volume, recon breaks aging > 24 h,
  webhook signature failures, provider callback anomalies, agent policy violations.
- Quarterly access review; break-glass access with automatic incident ticket.

## 7. Incident response

Severity ladder (S1 = funds at risk / ledger integrity): on-call page, war-room
protocol, provider freeze authority, post-mortem within 5 business days with decision-log
entry. Rehearsals: quarterly game-day (provider outage, ledger hot-standby failover).
