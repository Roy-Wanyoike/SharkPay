# SharkPay Kubernetes & GitOps manifests

Production manifests for the SharkPay platform, structured for **kustomize**
(base + per-environment overlays) and deployed by **Argo CD** (app-of-apps),
with **Argo Rollouts** canary + SLO-gated promotion on the money path.
Targets **Kubernetes 1.36** (ADR 001 §Infrastructure; "latest minus one" bump
policy).

Authority: `docs/adr/001-stack-lock.md`, `docs/adr/002-backend-strategy.md`
(active-active ≥2 AZ, gateway traffic weights, canary with SLO
auto-rollback, HPA). Env var names are sourced from each service's
`application.yml` / `cmd/server/main.go` (the compose rewrite does not
change this tree's contract).

## Layout

```
infrastructure/k8s/
├── README.md                     ← you are here
├── base/                         ── environment-neutral defaults
│   ├── namespace.yaml            Namespace sharkpay
│   ├── kustomization.yaml        composes everything below
│   ├── postgres-secret.example.yaml   Secret SHAPE example (never applied)
│   ├── secrets/<svc>.env.example     .env-style templates (never applied)
│   ├── network-policies/         default-deny + allow rules (7 policies)
│   ├── rollouts/                 AnalysisTemplates (success-rate, p99)
│   └── <svc>/                    one dir per service (10 services):
│       ├── rollout.yaml | deployment.yaml
│       ├── service.yaml          ClusterIP, port from application.yml
│       ├── configmap.yaml        app config mirroring application.yml
│       ├── hpa.yaml              CPU 70% target
│       └── pdb.yaml              minAvailable 50%
├── overlays/
│   ├── dev/                      1 replica, no canary steps, HPA 1..2,
│   │                             PDB maxUnavailable 1, :latest-dev tags
│   ├── staging/                  canary + analysis, HPA 2..6, tags here
│   └── prod/                     3..4 replicas, strict multi-AZ, HPA 2..10,
│                                 PDB minAvailable 2, prod resources,
│                                 immutable tags pinned by release
├── argocd/                       app-of-apps + AppProject + 3 Applications
│   └── README.md                 bootstrap & promote/rollback runbook
└── scripts/validate.py           python3 structural validator (CI-able)
```

### Services (10) — runtime, workload kind, port

| Service | Runtime | Kind | Port | Notes |
|---|---|---|---|---|
| ledger | Go | **Rollout** | 8090 | money path CRITICAL, probes `/healthz` + `/readyz` |
| providers | Go | **Rollout** | 8091 | money path, probe `/healthz`, external HoneyCoin egress |
| payments | Java | **Rollout** | 8085 | Temporal (7233), canary-gated |
| identity | Java | Deployment | 8081 | actuator probes |
| wallet | Java | Deployment | 8082 | actuator probes |
| fx | Java | Deployment | 8083 | actuator probes, external rate-provider egress |
| risk | Java | Deployment | 8084 | actuator probes |
| payouts | Java | Deployment | 8086 | Temporal (7233) |
| reconciliation | Java | Deployment | 8087 | NATS (4222) |
| api-gateway | Java | Deployment | 8080 | edge; receives traffic from `sharkpay-gateway` ns |

Go images: `ghcr.io/roy-wanyoike/sharkpay-ledger` etc. The `:PLACEHOLDER`
tag in base is the integrator sentinel — **never deploy base directly**;
every overlay sets a real tag (dev: `latest-dev`, staging: pinned by CI,
prod: pinned by release).

### Canary (ledger, providers, payments)

`maxSurge: 1, maxUnavailable: 0`; steps: **10% → pause 2m → 25% → pause 5m
→ 50% → analysis (success-rate ≥ 99% and p99 < 500ms over 5m, 10 × 30s
measurements, `failureLimit: 1`, `inconclusiveLimit: 3`) → 100%**. A failed
AnalysisRun aborts the rollout and Argo Rollouts reverts to the last stable
Revision — the ADR 002 §3 auto-rollback. The analysis queries Prometheus at
`http://prometheus.monitoring:9090` and expects the OTel-normalised metrics
`http_requests_total{service=...}` and
`http_request_duration_seconds_bucket{service=...}`; until services emit
those, analysis runs are Inconclusive (rollout pauses for a human) rather
than falsely green. Java services stay Deployments initially (per the plan)
except payments; migrating them is copy/paste of the Rollout shape once
their SLIs are wired.

## Cluster prerequisites (per environment)

1. Kubernetes 1.36, CNI with NetworkPolicy enforcement (Calico/Cilium —
   plain flannel silently ignores the policies in `base/network-policies/`).
2. Nodes labeled `topology.kubernetes.io/zone` (≥3 zones for prod
   strictness: `DoNotSchedule`).
3. Argo CD ≥ 2.10 and Argo Rollouts ≥ 1.7 installed (CRDs:
   `rollouts.argoproj.io`, `analysistemplates.argoproj.io`,
   `applications.argoproj.io`).
4. kube-prometheus-stack in namespace `monitoring`, service name
   `prometheus` (the analysis templates' address).
5. Data-layer namespaces: `sharkpay-postgres`, `sharkpay-redis`,
   `sharkpay-nats`, `sharkpay-keycloak`, `sharkpay-temporal`, and the edge
   namespace `sharkpay-gateway` (ingress controller / Spring Cloud Gateway
   edge tier). Names are load-bearing: the NetworkPolicies select them by
   the auto-assigned `kubernetes.io/metadata.name` label.

## Secrets (never in git)

Workloads read Secrets named `<svc>-secrets` (e.g. `ledger-secrets`).
Templates only — fill values out of band:

```bash
cp infrastructure/k8s/base/secrets/ledger.env.example ~/secrets/ledger.env
# edit ~/secrets/ledger.env with real values (never inside the repo)
kubectl -n sharkpay create secret generic ledger-secrets \
  --from-env-file=~/secrets/ledger.env
```

`base/postgres-secret.example.yaml` shows the equivalent k8s Secret YAML
shape (for sealed-secrets / external-secrets / SOPS integrations later).
Create the Secrets **before** the first sync — pods crash-loop loudly with
missing-secret errors (intended: no silent fake credentials on the money
path).

## Apply order (first bootstrap, GitOps afterwards)

With Argo CD (recommended — see `argocd/README.md`):

1. Install Argo CD + Argo Rollouts + Prometheus (prerequisites above).
2. Create namespace + secrets:
   `kubectl create namespace sharkpay` (or let Argo `CreateNamespace=true`
   do it — the base also declares the Namespace object) then the per-service
   Secrets.
3. Apply the root app: `kubectl apply -n argocd -f
   infrastructure/k8s/argocd/app-of-apps.yaml`. dev/staging sync
   automatically (selfHeal on, prune off); prod waits for a manual
   `argocd app sync sharkpay-prod`.

Without Argo CD (bare kustomize):

```bash
kubectl apply -f base/namespace.yaml
# ... create secrets as above ...
kubectl apply -k overlays/dev      # or staging/prod
```

The python validator runs the same checks CI runs:

```bash
python3 infrastructure/k8s/scripts/validate.py   # exit 0 = green
kustomize build infrastructure/k8s/overlays/prod | kubectl apply --dry-run=server -f -
```

## How CI image tags flow into overlays

GitOps rule: **the cluster only ever runs what git says, and git only ever
points at immutable image tags** (except dev's `latest-dev`).

1. CI builds images per service and pushes
   `ghcr.io/roy-wanyoike/sharkpay-<svc>:sha-<commit>`.
2. CI pins the tag **in the overlay** — the overlay directory is the only
   place tags live:
   ```bash
   cd infrastructure/k8s/overlays/staging
   kustomize edit set image \
     ghcr.io/roy-wanyoike/sharkpay-ledger=ghcr.io/roy-wanyoike/sharkpay-ledger:sha-abc1234
   git commit -am "staging: ledger sha-abc1234"
   ```
   Argo CD picks the commit up (automated sync for dev/staging) and the
   Rollout starts its canary ladder.
3. Prod promotion is a human action: same `kustomize edit set image` in
   `overlays/prod` via PR, then `argocd app sync sharkpay-prod`, then watch
   the canary: `kubectl argo rollouts get rollout ledger --watch`.
   `:PLACEHOLDER` in the prod overlay is the pre-first-release sentinel.

## Rollback procedure

* **Canary went bad (auto)**: a failed AnalysisRun aborts the Rollout and
  pods revert to the last stable ReplicaSet automatically — nothing to do
  but read `kubectl argo rollouts get rollout <svc>` and the AnalysisRun.
* **Canary paused / want out now**:
  `kubectl argo rollouts abort <svc>` (ledger/providers/payments), or
  `kubectl argo rollouts undo <svc>` to step back one revision.
* **Already promoted, need to go back**:
  ```bash
  kubectl argo rollouts undo ledger                 # Rollout services
  kubectl rollout undo deployment/wallet            # Deployment services
  ```
  then make git match reality (GitOps): revert the image-tag commit in the
  overlay (`git revert`) so selfHeal does not re-apply the bad tag.
* **Cluster-level (Argo CD history)**:
  `argocd app rollback sharkpay-dev <revision-id>` (disables selfHeal until
  re-enabled — re-enable after fixing git).
* **Full env rollback**: `git revert` the overlay tag-commit(s); for prod,
  sync manually. The base is versioned with the repo — `git checkout` of an
  older commit + `kustomize build | kubectl apply` is the escape hatch.

## Known integrator TODOs (by design)

* Fill image tags per overlay (CI does staging; release does prod).
* Create the real Secrets; decide sealed-secrets vs external-secrets.
* Point `HONEYCOIN_BASE_URL` (providers-config) at the real provider
  endpoint; today it is a placeholder host.
* Services must expose the Prometheus metrics the AnalysisTemplates query
  (OTel naming) — until then analysis is honestly Inconclusive.
* `fx`'s in-repo application.yml does not enable actuator probe groups; the
  k8s-mounted config does (noted in the ConfigMap comment).
* payments/payouts/reconciliation/api-gateway images do not exist yet
  (Wave 3 builds them); manifests are wired and waiting for tags.
