import { Suspense } from "react";
import type { Metadata } from "next";
import { PageShell } from "@/components/shared/PageShell";
import { StatTileSkeleton, TableSkeleton } from "@/components/ui/Skeleton";
import { WebhooksView } from "@/components/views/WebhooksView";
import { ApiClient } from "@/lib/api/client";
import { apiClientForSession } from "@/lib/api/server";
import { listWebhookEndpoints } from "@/lib/api/sdk/webhooks";
import { getSession } from "@/lib/auth/server";
import { loadWithFallback } from "@/lib/data/load";
import {
  seedDashboardMetrics,
  seedWebhookDeliveries,
  seedWebhookEndpointsPage,
} from "@/lib/seed/seed";

export const metadata: Metadata = {
  title: "Webhooks",
};

async function WebhooksData() {
  const session = await getSession();
  const client: ApiClient = session
    ? apiClientForSession(session)
    : new ApiClient({ accessToken: null });

  const endpoints = await loadWithFallback(
    "webhooks:endpoints",
    () => listWebhookEndpoints(client, {}),
    () => seedWebhookEndpointsPage(),
  );

  return (
    <WebhooksView
      endpoints={endpoints.data}
      deliveries={seedWebhookDeliveries}
      deliverySuccessPct={seedDashboardMetrics.webhookHealth.deliverySuccessPct}
      source={endpoints.source}
    />
  );
}

export default function WebhooksPage() {
  return (
    <PageShell
      title="Webhooks"
      description="Event delivery endpoints, retry/backoff state and dead-lettering — the ops view of the delivery SLA."
    >
      <Suspense
        fallback={
          <div className="space-y-6">
            <StatTileSkeleton count={3} />
            <TableSkeleton rows={4} columns={7} />
          </div>
        }
      >
        <WebhooksData />
      </Suspense>
    </PageShell>
  );
}
