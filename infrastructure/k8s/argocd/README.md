# Argo CD bootstrap & operations runbook

App-of-apps layout:

```
infrastructure/k8s/argocd/
├── app-of-apps.yaml           root Application (project: default) → argocd/apps/
└── apps/
    ├── project.yaml           AppProject "sharkpay" (sync-wave -1)
    ├── application-dev.yaml     overlay dev      — automated, selfHeal, no prune
    ├── application-staging.yaml overlay staging  — automated, selfHeal, no prune
    └── application-prod.yaml    overlay prod     — MANUAL sync (no automated block)
```

One Application per environment, each rendering
`infrastructure/k8s/overlays/<env>` with kustomize. Environments are
**separate clusters** (same namespace `sharkpay` everywhere); to target a
second cluster, add a destination to `AppProject.spec.destinations` and a
new Application — or later swap the child apps for a git-directory
`ApplicationSet` (the root app already lives in git, so the migration is
additive).

## 0. Prerequisites (per cluster)

```bash
# Argo CD
kubectl create namespace argocd
kubectl apply -n argocd -f https://raw.githubusercontent.com/argoproj/argo-cd/stable/manifests/install.yaml
# Argo Rollouts (CRDs + controller) — required by base/rollouts + Rollouts
kubectl apply -f https://raw.githubusercontent.com/argoproj/argo-rollouts/stable/install.yaml
# Prometheus (analysis provider) — kube-prometheus-stack, service prometheus.monitoring:9090
```

Verify the CRDs the manifests need:

```bash
kubectl get crd rollouts.argoproj.io analysistemplates.argoproj.io applications.argoproj.io
```

## 1. Bootstrap the app of apps

Option A — declarative (recommended):

```bash
kubectl apply -n argocd -f infrastructure/k8s/argocd/app-of-apps.yaml
```

Option B — CLI:

```bash
argocd login <argocd-host> --username admin --password "$(kubectl -n argocd get secret argocd-initial-admin-secret -o jsonpath='{.data.password}' | base64 -d)"
# (local: kubectl port-forward svc/argocd-server -n argocd 8080:443 ; argocd login localhost:8080 --insecure)
argocd app create sharkpay-app-of-apps \
  --repo https://github.com/Roy-Wanyoike/SharkPay.git \
  --path infrastructure/k8s/argocd/apps \
  --dest-server https://kubernetes.default.svc \
  --dest-namespace argocd \
  --project default \
  --self-heal
```

The root app materialises the AppProject first (sync-wave `-1`), then the
three environment Applications. **Create the per-service Secrets before
the first sync** (see `../README.md` "Secrets") or workloads will
crash-loop on missing env vars.

## 2. Day-2 operations

```bash
argocd app list                          # sharkpay-{dev,staging,prod}
argocd app get sharkpay-staging
argocd app sync sharkpay-prod            # manual prod gate
argocd app diff sharkpay-prod            # review before pressing the button
```

Sync policy contract:

| App | Sync | selfHeal | prune |
|---|---|---|---|
| sharkpay-app-of-apps | automated | true | false |
| sharkpay-dev | automated | true | false |
| sharkpay-staging | automated | true | false |
| sharkpay-prod | **manual** | — | false |

`prune: false` everywhere on purpose: deleting a manifest from git never
deletes a live object until the team explicitly flips it.

## 3. Canary: promote, watch, abort

```bash
kubectl argo rollouts get rollout ledger --watch        # live canary view
kubectl argo rollouts promote ledger                     # advance one step
kubectl argo rollouts promote ledger --full              # straight to 100%
kubectl argo rollouts abort ledger                       # abort → auto-revert
kubectl argo rollouts undo ledger                        # previous revision
kubectl argo rollouts status payments
```

At 50% weight the Rollouts run the inline analysis (AnalysisTemplates
`api-success-rate` + `api-p99-latency` from `base/rollouts/`): success rate
must stay ≥ 99% and p99 < 500ms over 5 minutes (10 × 30s measurements). A
failure beyond `failureLimit: 1` fails the run → the Rollout aborts and
reverts to the last stable ReplicaSet (ADR 002 auto-rollback). Inspect a
run with:

```bash
kubectl get analysisrun -n sharkpay
kubectl describe analysisrun <name> -n sharkpay
```

## 4. Rollback

See the parent `../README.md` "Rollback procedure" — short version:
`kubectl argo rollouts undo <svc>` (or `kubectl rollout undo
deployment/<svc>`), then `git revert` the overlay's image-tag commit so
selfHeal does not bring the bad tag back.
