import type { Payment, PaymentList } from "@/lib/api/sdk/payments";
import type { RiskCase, RiskCaseList } from "@/lib/api/sdk/risk";
import type { WebhookEndpoint, WebhookEndpointList } from "@/lib/api/sdk/webhooks";
import { DataOrigin } from "@/components/shared/DataOrigin";
import { PaymentStateBadge } from "@/components/shared/StateBadges";
import { Card, CardContent, CardHeader } from "@/components/ui/Card";
import { Skeleton, StatTileSkeleton, TableSkeleton } from "@/components/ui/Skeleton";
import { StatTile } from "@/components/ui/StatTile";
import { Table, type TableColumn } from "@/components/ui/Table";
import { formatDateTime, formatPercent, shortId } from "@/lib/format";
import { formatMoney } from "@/lib/money";
import type { DashboardMetrics } from "@/lib/seed/seed";

export interface DashboardViewProps {
  metrics: DashboardMetrics;
  recentPayments: PaymentList;
  riskCases: RiskCaseList;
  webhookEndpoints: WebhookEndpointList;
  sources: { payments: "api" | "seed"; risk: "api" | "seed"; webhooks: "api" | "seed" };
}

const PAYMENT_COLUMNS: Array<TableColumn<Payment>> = [
  {
    key: "id",
    header: "Payment",
    render: (row) => <span className="font-mono text-xs text-fg-muted">{shortId(row.id)}</span>,
  },
  { key: "state", header: "State", render: (row) => <PaymentStateBadge state={row.state} /> },
  {
    key: "amount",
    header: "Amount",
    align: "right",
    render: (row) => <span className="font-mono tabular-nums">{formatMoney(row.amount)}</span>,
  },
  { key: "rail", header: "Rail" },
  {
    key: "created_at",
    header: "Created",
    align: "right",
    render: (row) => <span className="text-fg-muted">{formatDateTime(row.created_at)}</span>,
  },
];

export function DashboardView({
  metrics,
  recentPayments,
  riskCases,
  webhookEndpoints,
  sources,
}: DashboardViewProps) {
  const openRisk = riskCases.items.filter(
    (riskCase) => riskCase.state === "OPEN" || riskCase.state === "INVESTIGATING",
  );
  const deadEndpoints = webhookEndpoints.items.filter((hook) => hook.state === "dead");

  return (
    <div className="space-y-6">
      <div className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4">
        <StatTile
          label="Payments today"
          value={metrics.paymentsToday.count.toLocaleString("en-GB")}
          hint={`${formatMoney(metrics.paymentsToday.volume)} volume`}
          delta={{
            direction: metrics.paymentsToday.deltaPct >= 0 ? "up" : "down",
            text: `${formatPercent(Math.abs(metrics.paymentsToday.deltaPct))} vs yesterday`,
          }}
        />
        <StatTile
          label="Payment success rate"
          value={formatPercent(metrics.successRatePct)}
          tone={metrics.successRatePct >= 98 ? "success" : "warning"}
          delta={{
            direction: metrics.successRateDeltaPts >= 0 ? "up" : "down",
            text: `${metrics.successRateDeltaPts >= 0 ? "+" : ""}${metrics.successRateDeltaPts.toFixed(1)} pts`,
          }}
          hint="24h rolling"
        />
        <StatTile
          label="Active recon breaks"
          value={metrics.activeBreaks}
          tone={metrics.activeBreaks > 0 ? "warning" : "success"}
          delta={{
            direction: metrics.breaksTrend <= 0 ? "up" : "down",
            text: `${Math.abs(metrics.breaksTrend)} since yesterday`,
          }}
          hint="provider vs ledger"
        />
        <StatTile
          label="Webhook delivery"
          value={formatPercent(metrics.webhookHealth.deliverySuccessPct)}
          tone={metrics.webhookHealth.deadEndpoints > 0 ? "danger" : "success"}
          hint={`${metrics.webhookHealth.deadEndpoints} dead · ${metrics.webhookHealth.pendingRetries} retrying`}
        />
      </div>

      <div className="grid gap-6 xl:grid-cols-3">
        <Card className="xl:col-span-2">
          <CardHeader
            title="Recent payments"
            description="Latest payment intents across all rails."
            actions={<DataOrigin source={sources.payments} />}
          />
          <Table
            caption="Recent payment intents"
            columns={PAYMENT_COLUMNS}
            rows={recentPayments.items.slice(0, 6)}
            rowKey={(row) => row.id}
            dense
            empty="No payments yet today."
          />
        </Card>

        <div className="space-y-6">
          <Card>
            <CardHeader
              title="Risk queue"
              description="Open and in-progress cases."
              actions={<DataOrigin source={sources.risk} />}
            />
            <CardContent className="space-y-3">
              {openRisk.slice(0, 3).map((riskCase) => (
                <div key={riskCase.id} className="flex items-start justify-between gap-2 text-sm">
                  <div className="min-w-0">
                    <p className="truncate font-mono text-xs text-fg-muted">{shortId(riskCase.id)}</p>
                    <p className="text-xs text-fg">{riskCase.rule.replace(/_/g, " ")}</p>
                  </div>
                  <span className="font-mono text-xs whitespace-nowrap tabular-nums text-fg-muted">
                    {formatMoney(riskCase.amount)}
                  </span>
                </div>
              ))}
              {openRisk.length === 0 ? (
                <p className="text-sm text-fg-muted">Queue is clear.</p>
              ) : null}
            </CardContent>
          </Card>

          <Card>
            <CardHeader
              title="Webhook endpoints"
              description="Delivery targets and their health."
              actions={<DataOrigin source={sources.webhooks} />}
            />
            <CardContent className="space-y-3">
              {webhookEndpoints.items.slice(0, 3).map((hook) => (
                <div key={hook.id} className="flex items-center justify-between gap-2">
                  <p className="truncate text-xs text-fg-muted">{safeHostname(hook.url)}</p>
                  <span
                    className={
                      hook.state === "dead"
                        ? "rounded-pill bg-danger-soft px-2 py-0.5 text-[10px] font-medium text-danger uppercase"
                        : "rounded-pill bg-success-soft px-2 py-0.5 text-[10px] font-medium text-success uppercase"
                    }
                  >
                    {hook.state}
                  </span>
                </div>
              ))}
              {deadEndpoints.length > 0 ? (
                <p className="text-xs text-danger">
                  {deadEndpoints.length} dead endpoint{deadEndpoints.length > 1 ? "s" : ""} — check the
                  Webhooks page.
                </p>
              ) : null}
            </CardContent>
          </Card>
        </div>
      </div>
    </div>
  );
}

function safeHostname(url: string): string {
  try {
    return new URL(url).hostname;
  } catch {
    return url;
  }
}

export function DashboardSkeleton() {
  return (
    <div className="space-y-6">
      <StatTileSkeleton count={4} />
      <div className="grid gap-6 xl:grid-cols-3">
        <div className="rounded-card border border-border-subtle bg-surface xl:col-span-2">
          <div className="border-b border-border-subtle px-5 py-4">
            <Skeleton className="h-4 w-40" />
          </div>
          <TableSkeleton rows={5} columns={5} />
        </div>
        <div className="space-y-6">
          {Array.from({ length: 2 }, (_, index) => (
            <div
              key={index}
              className="space-y-3 rounded-card border border-border-subtle bg-surface p-5"
            >
              <Skeleton className="h-4 w-32" />
              <Skeleton className="h-3 w-full" />
              <Skeleton className="h-3 w-2/3" />
              <Skeleton className="h-3 w-1/2" />
            </div>
          ))}
        </div>
      </div>
    </div>
  );
}
