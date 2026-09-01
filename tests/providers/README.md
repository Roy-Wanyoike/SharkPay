# Provider Conformance Suite (planned)

A provider implementation is production-eligible only after passing the
conformance suite in this directory (docs/PRD.md FR-702, docs/ROADMAP.md
Phase 4 exit criteria):

1. Quote happy path + unsupported-currency rejection
2. Initiate happy path (asserts idempotency header + audit record)
3. Poll status mapping for every status including UNKNOWN
4. Callback verification: forged signature, stale timestamp, replay
5. Cancel + unsupported-capability errors
6. Reverse + inverse status events
7. ReconcileReport window and line format
8. Failure injection: 5xx → circuit breaker opens → `ErrProviderUnavailable`
9. Timeouts: hanging provider → context deadline, call still audited

The HoneyCoin adapter tests in `services/providers/internal/honeycoin/`
cover the unit-level behavior; this suite will drive the adapters over HTTP
against WireMock (`docker-compose.yml`) for wire-level conformance.
