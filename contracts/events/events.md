# Event Catalog & Registry (v1)

Normative index of SharkPay's Kafka domain events. Every event published to
Kafka MUST have its schema registered here and in this directory; CI fails on
unregistered topics. Schema files are JSON Schema draft 2020-12, CloudEvents
1.0-aligned envelopes.

## Envelope (all events)

```json
{
  "id": "<uuid v7>",          // consumers dedupe on this
  "type": "<topic name>",     // equals the Kafka topic
  "specversion": "1.0",
  "source": "sharkpay/<service>",
  "subject": "<entity id>",
  "occurred_at": "<RFC 3339>",
  "data": { ... }
}
```

Delivery semantics: **at-least-once** per event; ordering is guaranteed only
per partition key (subject). Consumers must be idempotent on `id` and treat
business `state` fields as monotonic (see docs/STATE-MACHINES.md §7).

## Topic → schema registry

| Kafka topic | Schema | Producer | Notes |
|---|---|---|---|
| `payments.payment.created.v1` | `payments.payment.v1.json` | payments | intent accepted |
| `payments.payment.pending_provider.v1` | `payments.payment.v1.json` | payments | handed to provider |
| `payments.payment.succeeded.v1` | `payments.payment.v1.json` | payments | funds confirmed & captured |
| `payments.payment.failed.v1` | `payments.payment.v1.json` | payments | terminal failure (reason) |
| `payments.payment.expired.v1` | `payments.payment.v1.json` | payments | TTL elapsed unconfirmed |
| `payments.payment.reversed.v1` | `payments.payment.v1.json` | payments | compensation entry posted |
| `payouts.payout.created.v1` | `payouts.payout.v1.json` | payouts | accepted |
| `payouts.payout.processing.v1` | `payouts.payout.v1.json` | payouts | handed to provider |
| `payouts.payout.sent.v1` | `payouts.payout.v1.json` | payouts | rail accepted |
| `payouts.payout.succeeded.v1` | `payouts.payout.v1.json` | payouts | settled at destination |
| `payouts.payout.failed.v1` | `payouts.payout.v1.json` | payouts | failed at rail |
| `payouts.payout.returned.v1` | `payouts.payout.v1.json` | payouts | returned by rail |
| `transfers.transfer.succeeded.v1` | `transfers.transfer.v1.json` | payouts | internal transfer committed |
| `fx.quote.locked.v1` | `fx.v1.json` | fx | quote locked (TTL running) |
| `fx.conversion.executed.v1` | `fx.v1.json` | fx | 4-leg conversion posted |
| `wallet.balance.changed.v1` | `wallet.v1.json` | wallet | any partition change (projection) |
| `risk.decision.v1` | `risk.v1.json` | risk | allow / deny / review |
| `risk.case.opened.v1` | `risk.v1.json` | risk | case created |
| `risk.case.resolved.v1` | `risk.v1.json` | risk | case closed (resolution) |
| `ledger.posting.committed.v1` | `ledger.posting.v1.json` | ledger | **authoritative money feed** |

## Consumers of record

| Consumer | Subscribes to | Use |
|---|---|---|
| `wallet` | `ledger.posting.committed.v1` | balance projection (sole authority = ledger) |
| `payments` | `risk.decision.v1`, `providers.transfer.*` | orchestration gates |
| `payouts` | `risk.decision.v1`, `providers.transfer.*` | orchestration gates |
| `api-gateway` | all (fan-out) | webhook delivery (HMAC-signed, §4 API contracts) |
| `reconciliation` | `ledger.posting.committed.v1`, provider reports | break detection |

## Webhook mapping

Webhook payloads reuse the same envelope with the **unversioned catalog type**
from docs/API-CONTRACTS.md §4 (e.g. `payment.succeeded`, not
`payments.payment.succeeded.v1`), so public consumers are insulated from
internal topic versioning. Adding a topic = new schema file + row here +
webhook catalog entry.

## Rules

1. Topics are append-only: a topic's schema may gain optional fields, never
   rename/remove (the `/v1` suffix exists for breaking changes).
2. `data.state` values are monotonic; consumers must tolerate out-of-order
   arrivals across partitions.
3. `ledger.posting.committed.v1` is emitted exactly once per committed entry;
   an idempotent replay of the posting API does not re-emit.
4. Money in events is always integer minor units + currency (no floats).
