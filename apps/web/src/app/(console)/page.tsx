import { Suspense } from "react";
import type { Metadata } from "next";
import { DashboardSkeleton, DashboardView } from "@/components/views/DashboardView";
import { PageShell } from "@/components/shared/PageShell";
import { ApiClient } from "@/lib/api/client";
import { apiClientForSession } from "@/lib/api/server";
import { listPayments } from "@/lib/api/sdk/payments";
import { listRiskCases } from "@/lib/api/sdk/risk";
import { listWebhookEndpoints } from "@/lib/api/sdk/webhooks";
import { getSession } from "@/lib/auth/server";
import { loadWithFallback } from "@/lib/data/load";
import {
  seedDashboardMetrics,
  seedPaymentsPage,
  seedRiskCasesPage,
  seedWebhookEndpointsPage,
} from "@/lib/seed/seed";

export const metadata: Metadata = {
  title: "Dashboard",
};

async function DashboardData() {
  const session = await getSession();
  const client: ApiClient = session
    ? apiClientForSession(session)
    : new ApiClient({ accessToken: null });

  const [payments, risk, webhooks] = await Promise.all([
    loadWithFallback("dashboard:payments", () => listPayments(client, { limit: 10 }), () =>
      seedPaymentsPage({ limit: 10 }),
    ),
    loadWithFallback("dashboard:risk", () => listRiskCases(client, {}), () => seedRiskCasesPage()),
    loadWithFallback("dashboard:webhooks", () => listWebhookEndpoints(client, {}), () =>
      seedWebhookEndpointsPage(),
    ),
  ]);

  return (
    <DashboardView
      // Metrics are aggregate-only for now — there is no metrics endpoint in
      // the merged contracts; the seed values are clearly marked in the UI.
      metrics={seedDashboardMetrics}
      recentPayments={payments.data}
      riskCases={risk.data}
      webhookEndpoints={webhooks.data}
      sources={{
        payments: payments.source,
        risk: risk.source,
        webhooks: webhooks.source,
      }}
    />
  );
}

export default function DashboardPage() {
  return (
    <PageShell
      title="Dashboard"
      description="Today's money movement, payment health, reconciliation breaks and webhook delivery."
    >
      <Suspense fallback={<DashboardSkeleton />}>
        <DashboardData />
      </Suspense>
    </PageShell>
  );
}
