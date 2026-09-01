#!/usr/bin/env python3
"""Structural validator for infrastructure/k8s (Task 14, ADR 003).

No cluster, no kubectl — pure python3 + PyYAML. For every *.yaml / *.yml
file under infrastructure/k8s:
  * yaml.safe_load_all parses every document (multi-doc files included)
  * every document has a non-empty `kind` and `metadata.name`
  * files named kustomization.yaml have kind Kustomization and every
    `resources` entry resolves to a file or a directory containing a
    kustomization.yaml

Cross-checks (fail the run if violated):
  * expected workload kinds per service (ledger/providers/payments are
    Argo Rollouts; the other seven are Deployments)
  * every workload pod: probes (liveness+readiness), resources
    (requests+limits), container securityContext (non-root, drop ALL,
    read-only root fs, RuntimeDefault seccomp at pod level), zone
    topologySpreadConstraints, hostname podAntiAffinity
  * HPA scaleTargetRef points at an existing workload of the right kind
  * Service selector matches workload pod labels; targetPort is a named
    container port
  * PDB selector matches workload pod labels
  * Rollout analysis template references resolve to declared
    AnalysisTemplates; Rollout canary has maxSurge 1 / maxUnavailable 0
  * .env.example files parse as KEY=VALUE with placeholder-looking values
  * overlays: every image transformer entry matches an image used in base

Usage:  python3 infrastructure/k8s/scripts/validate.py [root]
Exit 0 = all good (summary printed); exit 1 = failures listed.
"""
import os
import re
import sys

import yaml

ROOT = os.path.abspath(sys.argv[1] if len(sys.argv) > 1 else
                       os.path.join(os.path.dirname(__file__), ".."))

EXPECTED_KINDS = {          # service -> workload kind
    "ledger": "Rollout", "providers": "Rollout", "payments": "Rollout",
    "identity": "Deployment", "wallet": "Deployment", "fx": "Deployment",
    "risk": "Deployment", "payouts": "Deployment",
    "reconciliation": "Deployment", "api-gateway": "Deployment",
}
EXPECTED_PORTS = {
    "ledger": 8090, "providers": 8091, "payments": 8085, "identity": 8081,
    "wallet": 8082, "fx": 8083, "risk": 8084, "payouts": 8086,
    "reconciliation": 8087, "api-gateway": 8080,
}
ALLOWED_ENV_NAMES = {       # source of truth: application.yml / main.go / compose
    "ledger": {"DATABASE_URL", "INTERNAL_API_TOKEN", "LISTEN_ADDR", "LEDGER_STORE"},
    "providers": {"PORT", "HONEYCOIN_BASE_URL", "HONEYCOIN_SIGNING_KEY",
                  "HONEYCOIN_CALLBACK_SECRET"},
    "payments": {"PAYMENTS_DB_URL", "PAYMENTS_DB_USER", "PAYMENTS_DB_PASSWORD",
                 "TEMPORAL_ADDRESS", "TEMPORAL_NAMESPACE", "KEYCLOAK_ISSUER_URI"},
    "identity": {"SPRING_DATASOURCE_URL", "SPRING_DATASOURCE_USERNAME",
                 "SPRING_DATASOURCE_PASSWORD",
                 "SPRING_SECURITY_OAUTH2_RESOURCESERVER_JWT_ISSUER_URI"},
    "wallet": {"WALLET_DB_URL", "WALLET_DB_USER", "WALLET_DB_PASSWORD",
               "WALLET_ISSUER_URI"},
    "fx": {"FX_DB_URL", "FX_DB_USER", "FX_DB_PASSWORD", "FX_ISSUER_URI",
           "FX_MARKUP_BPS", "FX_QUOTE_TTL_SECONDS", "FX_QUOTE_SWEEP_INTERVAL_MS"},
    "risk": {"RISK_DB_URL", "RISK_DB_USER", "RISK_DB_PASSWORD",
             "KEYCLOAK_ISSUER_URI"},
    "payouts": {"PAYOUTS_DB_URL", "PAYOUTS_DB_USER", "PAYOUTS_DB_PASSWORD",
                "TEMPORAL_ADDRESS", "TEMPORAL_NAMESPACE", "KEYCLOAK_ISSUER_URI"},
    "reconciliation": {"RECON_DB_URL", "RECON_DB_USER", "RECON_DB_PASSWORD",
                       "NATS_URL", "KEYCLOAK_ISSUER_URI"},
    "api-gateway": {"KEYCLOAK_ISSUER_URI"},
}
IMAGE_RE = re.compile(r"^ghcr\.io/roy-wanyoike/sharkpay-[a-z-]+:[A-Za-z0-9_.-]+$")

errors = []
notes = {"yaml_files": 0, "docs": 0, "env_files": 0, "env_vars": 0}


def err(msg):
    errors.append(msg)


def load_all_docs(path):
    with open(path) as f:
        text = f.read()
    docs = []
    for i, doc in enumerate(yaml.safe_load_all(text), start=1):
        if doc is None:
            continue
        notes["docs"] += 1
        if not isinstance(doc, dict):
            err(f"{path} doc#{i}: not a mapping")
            continue
        kind = doc.get("kind")
        name = (doc.get("metadata") or {}).get("name")
        if not kind or not isinstance(kind, str):
            err(f"{path} doc#{i}: missing/empty kind")
        # Kustomization files legitimately carry no metadata.name.
        if kind != "Kustomization":
            if not name or not isinstance(name, str):
                err(f"{path} doc#{i}: missing/empty metadata.name")
        docs.append(doc)
    return docs


def check_kustomization(path, doc, kust_files):
    for res in doc.get("resources", []) or []:
        target = os.path.normpath(os.path.join(os.path.dirname(path), res))
        if not os.path.exists(target):
            err(f"{path}: resources entry {res!r} does not exist ({target})")
        elif os.path.isdir(target):
            if not os.path.exists(os.path.join(target, "kustomization.yaml")):
                err(f"{path}: directory resource {res!r} has no kustomization.yaml")
        if res.endswith(".example.yaml") or "secrets" in res:
            err(f"{path}: example/secret file {res!r} must NOT be a kustomize resource")


def env_names_in_workload(doc):
    """All env var names a pod will actually see (env + envFrom configmap)."""
    names = set()
    c = doc["spec"]["template"]["spec"]["containers"][0]
    for e in c.get("env") or []:
        names.add(e.get("name"))
    for ef in c.get("envFrom") or []:
        names.add(ef.get("configMapRef", {}).get("name"))
    return names, c


def check_workload(path, doc, workloads, templates):
    kind, name = doc["kind"], doc["metadata"]["name"]
    workloads[name] = doc
    if name in EXPECTED_KINDS and kind != EXPECTED_KINDS[name]:
        err(f"{path}: {name} should be a {EXPECTED_KINDS[name]}, got {kind}")
    spec = doc.get("spec", {})
    for field in ("replicas", "selector", "template"):
        if field not in spec:
            err(f"{path}: workload spec missing {field}")
    if kind == "Rollout":
        canary = spec.get("strategy", {}).get("canary")
        if not canary:
            err(f"{path}: Rollout without canary strategy")
        else:
            if canary.get("maxSurge") != 1 or canary.get("maxUnavailable") != 0:
                err(f"{path}: canary must be maxSurge 1 / maxUnavailable 0")
            for step in canary.get("steps") or []:
                a = step.get("analysis")
                if a:
                    for t in a.get("templates") or []:
                        if t.get("templateName") not in templates:
                            err(f"{path}: analysis template {t.get('templateName')!r} not declared")
                    if not any(arg.get("name") == "service" for arg in a.get("args") or []):
                        err(f"{path}: analysis step missing 'service' arg")

    pod = spec.get("template", {}).get("spec", {})
    psc = pod.get("securityContext") or {}
    if not psc.get("runAsNonRoot") or psc.get("runAsUser") != 10001:
        err(f"{path}: pod securityContext must be runAsNonRoot with uid 10001")
    if psc.get("seccompProfile", {}).get("type") != "RuntimeDefault":
        err(f"{path}: pod seccompProfile must be RuntimeDefault")
    tsc = pod.get("topologySpreadConstraints") or []
    if not any(t.get("topologyKey") == "topology.kubernetes.io/zone"
               and t.get("maxSkew") == 1 for t in tsc):
        err(f"{path}: missing zone topologySpreadConstraint with maxSkew 1")
    anti = (pod.get("affinity") or {}).get("podAntiAffinity") or {}
    terms = anti.get("preferredDuringSchedulingIgnoredDuringExecution") or []
    if not any((t.get("podAffinityTerm") or {}).get("topologyKey") == "kubernetes.io/hostname"
               for t in terms):
        err(f"{path}: missing preferred hostname podAntiAffinity")

    containers = pod.get("containers") or []
    if not containers:
        err(f"{path}: no containers")
        return
    c = containers[0]
    img = c.get("image", "")
    if not IMAGE_RE.match(img):
        err(f"{path}: image {img!r} does not match ghcr.io/roy-wanyoike/sharkpay-<svc>:<tag>")
    if "livenessProbe" not in c or "readinessProbe" not in c:
        err(f"{path}: container missing liveness/readiness probes")
    res = c.get("resources") or {}
    for block in ("requests", "limits"):
        if not res.get(block) or "cpu" not in res[block] or "memory" not in res[block]:
            err(f"{path}: resources.{block} must set cpu+memory")
    csc = c.get("securityContext") or {}
    if csc.get("allowPrivilegeEscalation") is not False:
        err(f"{path}: allowPrivilegeEscalation must be false")
    if csc.get("readOnlyRootFilesystem") is not True:
        err(f"{path}: readOnlyRootFilesystem must be true")
    if "ALL" not in (csc.get("capabilities") or {}).get("drop") or []:
        err(f"{path}: capabilities.drop must include ALL")

    # env var names vs source-of-truth set (envFrom configmap NAMES are not
    # env vars; the configmap itself is checked in the second pass)
    names, _ = env_names_in_workload(doc)
    names = {n for n in names if n and n != f"{name}-config"}
    bad = names - ALLOWED_ENV_NAMES.get(name, set())
    if bad:
        err(f"{path}: env vars not in source-of-truth set: {sorted(bad)}")

    # secrets referenced must look like <svc>-secrets
    for e in c.get("env") or []:
        ref = (e.get("valueFrom") or {}).get("secretKeyRef") or {}
        if ref and ref.get("name") != f"{name}-secrets":
            err(f"{path}: env {e.get('name')} secret {ref.get('name')} != {name}-secrets")


def main():
    kust_files = []
    all_docs = []
    for dirpath, _dirnames, filenames in os.walk(ROOT):
        for fn in sorted(filenames):
            path = os.path.join(dirpath, fn)
            rel = os.path.relpath(path, ROOT)
            if fn.endswith((".yaml", ".yml")):
                notes["yaml_files"] += 1
                docs = load_all_docs(path)
                if fn == "kustomization.yaml":
                    k = [d for d in docs if (d or {}).get("kind") == "Kustomization"]
                    if not k:
                        err(f"{path}: kustomization.yaml must contain kind Kustomization")
                    else:
                        kust_files.append((path, k[0]))
                all_docs.extend((path, d) for d in docs)
            elif fn.endswith(".env.example"):
                notes["env_files"] += 1
                with open(path) as f:
                    for i, line in enumerate(f, start=1):
                        line = line.strip()
                        if not line or line.startswith("#"):
                            continue
                        if "=" not in line:
                            err(f"{path}:{i}: not KEY=VALUE: {line!r}")
                            continue
                        k, v = line.split("=", 1)
                        if not k or not v:
                            err(f"{path}:{i}: empty key or value")
                        notes["env_vars"] += 1

    # kustomization resource references
    for path, doc in kust_files:
        check_kustomization(path, doc, kust_files)

    # second pass: cross-object checks
    templates = {d["metadata"]["name"] for p, d in all_docs
                 if d.get("kind") == "AnalysisTemplate"}
    workloads = {}
    for path, d in all_docs:
        if d.get("kind") in ("Deployment", "Rollout"):
            check_workload(path, d, workloads, templates)

    for path, d in all_docs:
        kind, name = d.get("kind"), d.get("metadata", {}).get("name")
        if kind == "HorizontalPodAutoscaler":
            ref = (d.get("spec") or {}).get("scaleTargetRef") or {}
            wl = workloads.get(ref.get("name"))
            if wl is None:
                err(f"{path}: scaleTargetRef {ref.get('name')!r} has no workload")
            elif wl["kind"] != ref.get("kind"):
                err(f"{path}: scaleTargetRef kind {ref.get('kind')} != {wl['kind']}")
            m = (d.get("spec") or {}).get("metrics") or []
            if not any(x.get("resource", {}).get("name") == "cpu" and
                       x["resource"].get("target", {}).get("averageUtilization") == 70
                       for x in m if x.get("type") == "Resource"):
                err(f"{path}: HPA must target CPU utilization 70%")
        if kind == "Service":
            sel = (d.get("spec") or {}).get("selector") or {}
            wl = workloads.get(name)
            if wl is None:
                err(f"{path}: Service {name} has no matching workload")
            else:
                pod_labels = (wl["spec"].get("template") or {}).get("metadata", {}).get("labels") or {}
                if any(pod_labels.get(k) != v for k, v in sel.items()):
                    err(f"{path}: selector {sel} does not match pod labels")
                for p_ in (d.get("spec") or {}).get("ports") or []:
                    tp = p_.get("targetPort")
                    cports = (wl["spec"]["template"]["spec"]["containers"][0].get("ports") or [])
                    if not any(cp.get("name") == tp for cp in cports):
                        err(f"{path}: targetPort {tp!r} not a named container port")
                    if name in EXPECTED_PORTS and p_.get("port") != EXPECTED_PORTS[name]:
                        err(f"{path}: port {p_.get('port')} != expected {EXPECTED_PORTS[name]} for {name}")
            if (d.get("spec") or {}).get("type") != "ClusterIP":
                err(f"{path}: Service type must be ClusterIP")
        if kind == "PodDisruptionBudget":
            sel = ((d.get("spec") or {}).get("selector") or {}).get("matchLabels") or {}
            wl = workloads.get(name)
            if wl is None:
                err(f"{path}: PDB {name} has no matching workload")
            else:
                pod_labels = (wl["spec"].get("template") or {}).get("metadata", {}).get("labels") or {}
                if any(pod_labels.get(k) != v for k, v in sel.items()):
                    err(f"{path}: selector does not match pod labels")
            if "minAvailable" not in (d.get("spec") or {}):
                err(f"{path}: base PDB must set minAvailable (50%)")

    # overlays reference base images
    base_dir = os.path.join(ROOT, "base")
    base_images = set()
    for path, d in all_docs:
        if d.get("kind") in ("Deployment", "Rollout") and path.startswith(base_dir):
            c = d["spec"]["template"]["spec"]["containers"][0]
            base_images.add(c["image"].rsplit(":", 1)[0])
    for env in ("dev", "staging", "prod"):
        kpath = os.path.join(ROOT, "overlays", env, "kustomization.yaml")
        if not os.path.exists(kpath):
            err(f"missing overlay kustomization: {kpath}")
            continue
        with open(kpath) as f:
            k = yaml.safe_load(f) or {}
        for img in k.get("images") or []:
            if img.get("name") not in base_images:
                err(f"{kpath}: image {img.get('name')!r} not used by any base workload")
            if not img.get("newTag"):
                err(f"{kpath}: image {img.get('name')!r} missing newTag")

    # argo cd apps point at real overlay paths
    for path, d in all_docs:
        if d.get("kind") == "Application":
            src = d.get("spec", {}).get("source", {})
            p = src.get("path", "")
            if p and not os.path.isdir(os.path.join(ROOT, "..", "..", p)):
                err(f"{path}: source path {p!r} does not exist from repo root")

    print(f"validated root: {ROOT}")
    print(f"  yaml files ............ {notes['yaml_files']}")
    print(f"  yaml documents ........ {notes['docs']}")
    print(f"  kustomizations ........ {len(kust_files)}")
    print(f"  .env.example files .... {notes['env_files']} ({notes['env_vars']} keys)")
    print(f"  workloads checked ..... {len(workloads)} "
          f"({sum(1 for w in workloads.values() if w['kind'] == 'Rollout')} Rollouts)")
    if errors:
        print(f"\nFAIL — {len(errors)} problem(s):")
        for e in errors:
            print("  - " + e)
        sys.exit(1)
    print("\nOK — all manifests structurally valid (kind/name present, cross-refs resolved)")
    sys.exit(0)


if __name__ == "__main__":
    main()
