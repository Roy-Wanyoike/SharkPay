<!-- SharkPay pull request template — auto-applied to every PR.
     ADR 003 §5: "the PR body carries the evidence matrix; reviewers (or the
     owner) can audit the ladder without re-running everything."
     Full protocol: docs/adr/003-safe-parallelism-and-verification.md -->

## Summary

<!-- What & why, 1–3 sentences. One PR per wave or coherent feature (ADR 003 §5);
     created by the integrator from a feature branch — never a direct push to main. -->

## Verification ladder evidence (REQUIRED)

PR creation is permitted only after the full ladder is green locally:
`scripts/verify-all.sh` must exit **0** (ADR 003 §4). Embed its complete
output — the verification matrix plus the final
`ALL GATES GREEN — PR creation permitted` line — below, including the exit
code. **A PR body without this evidence fails review**, and CI
(`.github/workflows/verify.yml`) re-runs the same ladder as the final
independent check before merge.

```text
$ scripts/verify-all.sh
<!-- PASTE THE FULL MATRIX OUTPUT HERE, e.g.:
PASS   G4 contracts                openapi + event schemas parse
PASS   go:packages/go/money        build+vet+fmt+test green
PASS   go:services/ledger          build+vet+fmt+test green
...
PASS   java:packages/java/sharkpay-money   clean verify green (surefire + jacoco >= 0.80)
PASS   java:services/identity      clean verify green (surefire + jacoco >= 0.80)
...
ALL GATES GREEN — PR creation permitted
-->
exit code: <!-- 0 -->
```

## Evidence matrix

<!-- Copy one row per module this PR touches, straight from the matrix above.
     Keep the columns: G3 must carry the actual coverage number (the JaCoCo
     BUNDLE LINE >= 0.80 gate is build-enforced per service pom; Go uses
     `go test -cover`). Do not delete rows for modules you touched. -->

| module | G1 compile | G2 tests | G3 coverage | notes |
|--------|-----------|----------|-------------|-------|
| packages/go/money | ✅ | ✅ N/N | e.g. 95.9% | must be race-clean (`go test -race`) |
| packages/java/sharkpay-money | ✅ | ✅ 45/45 | e.g. 91.0% | 1:1 port of the Go money lib (parity tests) |
| services/identity | ✅ | ✅ N/N | e.g. 92.1% | JaCoCo LINE >= 0.80 build-enforced |
| services/wallet | ✅ | ✅ 219/219 | e.g. 93.7% | money-safety invariant tests attached |
| … | | | | |

## G1–G5 checklist

<!-- All five gates, checked against the verify-all.sh output pasted above. -->

- [ ] **G1 — compile**: `go build ./... && go vet ./...` per Go module · `mvn clean compile` (via `clean verify`) per Java module
- [ ] **G2 — tests**: full unit suites green; money-safety tests present wherever money moves — idempotency (same key ⇒ same result, no double effect), non-negative balance invariants, currency-mismatch rejection, overflow rejection, no-float audit (`double`/`float` grep empty in money paths)
- [ ] **G3 — coverage**: JaCoCo BUNDLE LINE ≥ 0.80 (enforced by build failure, not by report) · `go test -cover` ≥ 80% on Go domain+service packages
- [ ] **G4 — contracts**: every `contracts/openapi/v1/*.yaml` parses as OpenAPI and every `contracts/events/*.json` parses; **no merged contract modified** — `git diff --stat contracts/` shows additions only
- [ ] **G5 — integration rebuild**: integrator rebuilt every module from a clean tree (cross-runtime matrix re-run); `docker compose config -q` green
- [ ] CI gate `.github/workflows/verify.yml` green on this PR — jobs `contracts`, `go`, `java`, `web`, `compose-config`, `gate`

## Contract additions (append-only rule — ADR 003 §2)

<!-- Contracts are the ONLY integration seam between runtimes. Merged
     contract files are frozen; new uniquely-named files are allowed,
     modifications are an integration-time decision. If this PR touches NO
     contract files, leave every box unchecked and write "none". -->

- [ ] No already-merged file under `contracts/` was modified (additions only)
- [ ] Every new contract file is uniquely named (no shadowing/overwriting an existing schema)
- [ ] New event payloads structurally validate against their CloudEvents JSON schema
- [ ] OpenAPI handlers added in services match `contracts/openapi/v1` paths and shapes

Contract files touched (paths, or "none"):

<!-- e.g. contracts/events/wallet.holds.v1.json (NEW, append-only) -->

## Notes for reviewers

<!-- Anything that made you deviate from the letter of a task/ADR, salvage
     decisions, remaining follow-ups. If nothing: delete this section. -->

## References

- ADR 003 — safe parallelism & pre-PR verification: [`docs/adr/003-safe-parallelism-and-verification.md`](docs/adr/003-safe-parallelism-and-verification.md)
- Local gate: `scripts/verify-all.sh` · CI gate (same ladder): `.github/workflows/verify.yml`
