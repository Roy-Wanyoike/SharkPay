import { Suspense } from "react";
import type { Metadata } from "next";
import { PageShell } from "@/components/shared/PageShell";
import { StatTileSkeleton, TableSkeleton } from "@/components/ui/Skeleton";
import { RiskView } from "@/components/views/RiskView";
import { ApiClient } from "@/lib/api/client";
import { apiClientForSession } from "@/lib/api/server";
import { listRiskCases, type RiskCaseState } from "@/lib/api/sdk/risk";

const RISK_STATES: readonly string[] = ["OPEN", "INVESTIGATING", "RESOLVED", "DISMISSED"];

function asRiskState(value: string | undefined): RiskCaseState | undefined {
  return value && RISK_STATES.includes(value) ? (value as RiskCaseState) : undefined;
}
import { getSession } from "@/lib/auth/server";
import { loadWithFallback } from "@/lib/data/load";
import { seedRiskCasesPage } from "@/lib/seed/seed";

export const metadata: Metadata = {
  title: "Risk Cases",
};

interface PageSearchParams {
  state?: string;
}

async function RiskData({ state }: PageSearchParams) {
  const session = await getSession();
  const client: ApiClient = session
    ? apiClientForSession(session)
    : new ApiClient({ accessToken: null });

  const result = await loadWithFallback(
    "risk:list",
    () => listRiskCases(client, { state: asRiskState(state) }),
    () => seedRiskCasesPage({ state }),
  );

  return <RiskView page={result.data} source={result.source} filters={{ state }} />;
}

export default async function RiskPage({
  searchParams,
}: {
  searchParams?: Promise<PageSearchParams>;
}) {
  const params = (await searchParams) ?? {};
  return (
    <PageShell
      title="Risk Cases"
      description="Rule hits and investigations — velocity limits, structuring patterns, sanctions screening and KYC mismatches."
    >
      <Suspense
        fallback={
          <div className="space-y-4">
            <StatTileSkeleton count={3} />
            <TableSkeleton rows={6} columns={8} />
          </div>
        }
      >
        <RiskData state={params.state} />
      </Suspense>
    </PageShell>
  );
}
