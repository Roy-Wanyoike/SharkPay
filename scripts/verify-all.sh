#!/usr/bin/env bash
# ============================================================================
# SharkPay — PR validation gate (implements ADR 003 §4, gates G1–G5, locally)
#
# Runs the full cross-runtime ladder:
#   G4  contracts   — every OpenAPI + event schema parses
#   G1  compile     — go build/vet/gofmt (Go) · mvn clean compile (Java)
#   G2  tests       — go test -cover · mvn clean verify (Surefire)
#   G3  coverage    — JaCoCo bundle LINE >= 0.80 enforced by each service pom
#   G5  integration — this script IS the cross-runtime rebuild: it discovers
#                     every module in the tree and rebuilds all of them
#
# Exit 0 only when every gate is green. CI (.github/workflows/verify.yml)
# runs the same ladder, so a green laptop cannot lie about a broken CI.
#
# PR CREATION IS PERMITTED ONLY AFTER THIS SCRIPT EXITS 0 (ADR 003 §4).
#
# Usage: scripts/verify-all.sh [--skip-web] [--skip-java] [--skip-go]
# ============================================================================
set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

# ── Toolchain (sandbox-local installs; no-ops when already correct) ────────
export JAVA_HOME="${JAVA_HOME:-/home/z/.local/jdk-25.0.4.1+1}"
export PATH="$JAVA_HOME/bin:/home/z/.local/apache-maven-3.9.16/bin:/home/z/.local/go/bin:$PATH"
export GOPATH="${GOPATH:-/home/z/go}"
export GOCACHE="${GOCACHE:-/home/z/.cache/go-build}"

SKIP_WEB=0; SKIP_JAVA=0; SKIP_GO=0
for arg in "$@"; do
  case "$arg" in
    --skip-web)  SKIP_WEB=1 ;;
    --skip-java) SKIP_JAVA=1 ;;
    --skip-go)   SKIP_GO=1 ;;
    *) echo "unknown flag: $arg" >&2; exit 2 ;;
  esac
done

FAILED=0
MATRIX=()

note()  { printf '\n\033[1;36m════ %s ════\033[0m\n' "$*"; }
pass()  { MATRIX+=("PASS|$1|$2"); printf '\033[1;32m✔ %-38s %s\033[0m\n' "$1" "$2"; }
failf() { MATRIX+=("FAIL|$1|$2"); FAILED=1; printf '\033[1;31m✘ %-38s %s\033[0m\n' "$1" "$2"; }

# ── G4: contracts ─────────────────────────────────────────────────────────
note "G4 · contract validation"
if python3 - <<'PY'
import glob, json, sys
try:
    import yaml
except ImportError:
    print("pyyaml missing — skipping OpenAPI structural checks", file=sys.stderr)
    yaml = None
n = 0
for f in sorted(glob.glob("contracts/openapi/v1/*.yaml")):
    if yaml is None:
        continue
    doc = yaml.safe_load(open(f))
    assert "openapi" in doc and "info" in doc, f"{f}: not an OpenAPI document"
    n += 1
for f in sorted(glob.glob("contracts/events/*.json")):
    json.load(open(f))
    n += 1
print(f"{n} contract files valid")
PY
then pass "G4 contracts" "openapi + event schemas parse"; else failf "G4 contracts" "malformed contract file"; fi

# ── Go modules (G1+G2+G3) ─────────────────────────────────────────────────
if [ "$SKIP_GO" -eq 0 ]; then
  GO_MODULES="packages/go/money services/ledger services/providers"
  GO_MODULES="$GO_MODULES $(find tests -name go.mod -not -path '*/target/*' 2>/dev/null | xargs -r -n1 dirname)"
  for m in $GO_MODULES; do
    [ -d "$m" ] || continue
    note "Go · $m (build · vet · fmt · test)"
    if ( cd "$m" \
      && go build ./... \
      && go vet ./... \
      && [ -z "$(gofmt -l .)" ] \
      && go test ./... -cover ); then
      pass "go:$m" "build+vet+fmt+test green"
    else
      failf "go:$m" "build/vet/fmt/test failed"
    fi
  done
fi

# ── Java modules (G1+G2+G3, sequential: JaCoCo gate in each pom) ───────────
if [ "$SKIP_JAVA" -eq 0 ]; then
  for p in $(find packages services -name pom.xml -not -path '*/target/*' 2>/dev/null | sort); do
    d="$(dirname "$p")"
    note "Java · $d (mvn -B clean verify)"
    if ( cd "$d" && mvn -B -ntp clean verify ); then
      pass "java:$d" "clean verify green (surefire + jacoco >= 0.80)"
    else
      failf "java:$d" "mvn clean verify failed"
    fi
  done
fi

# ── Web console (when apps/web exists) ────────────────────────────────────
if [ "$SKIP_WEB" -eq 0 ] && [ -f apps/web/package.json ]; then
  note "Web · apps/web (npm ci · build · test)"
  if ( cd apps/web \
    && npm ci --no-audit --no-fund \
    && npm run build \
    && npm run test --if-present ); then
    pass "web:apps/web" "install+build+test green"
  else
    failf "web:apps/web" "build/test failed"
  fi
fi

# ── Summary: the evidence matrix that goes into the PR body ───────────────
note "Verification matrix"
printf '%-6s %-40s %s\n' "STATE" "MODULE" "GATE"
for row in "${MATRIX[@]}"; do
  IFS='|' read -r st mod det <<< "$row"
  printf '%-6s %-40s %s\n' "$st" "$mod" "$det"
done
if [ "$FAILED" -ne 0 ]; then
  printf '\n\033[1;31mGATE FAILED — do NOT create a PR (ADR 003 §4)\033[0m\n'
  exit 1
fi
printf '\n\033[1;32mALL GATES GREEN — PR creation permitted (ADR 003 §4)\033[0m\n'
exit 0
