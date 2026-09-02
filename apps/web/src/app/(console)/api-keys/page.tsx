import { Suspense } from "react";
import type { Metadata } from "next";
import { PageShell } from "@/components/shared/PageShell";
import { TableSkeleton } from "@/components/ui/Skeleton";
import { ApiKeysView } from "@/components/views/ApiKeysView";
import { ApiClient } from "@/lib/api/client";
import { apiClientForSession } from "@/lib/api/server";
import { listApiKeys } from "@/lib/api/sdk/apikeys";
import { getSession } from "@/lib/auth/server";
import { loadWithFallback } from "@/lib/data/load";
import { seedApiKeysPage } from "@/lib/seed/seed";

export const metadata: Metadata = {
  title: "API Keys",
};

async function ApiKeysData() {
  const session = await getSession();
  const client: ApiClient = session
    ? apiClientForSession(session)
    : new ApiClient({ accessToken: null });

  const result = await loadWithFallback(
    "api-keys:list",
    () => listApiKeys(client, {}),
    () => seedApiKeysPage(),
  );

  return <ApiKeysView page={result.data} source={result.source} />;
}

export default function ApiKeysPage() {
  return (
    <PageShell
      title="API Keys"
      description="Scoped keys for programmatic access — sp_test_/sp_live_ prefixes, policy-bound agent keys, write-only secrets."
    >
      <Suspense fallback={<TableSkeleton rows={5} columns={7} />}>
        <ApiKeysData />
      </Suspense>
    </PageShell>
  );
}
