import type { WebhookDelivery, WebhookEndpointList, WebhookEndpoint } from "@/lib/api/sdk/webhooks";
import { DataOrigin } from "@/components/shared/DataOrigin";
import { DeliveryStateBadge, WebhookEndpointStateBadge } from "@/components/shared/StateBadges";
import { Card } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/EmptyState";
import { StatTile } from "@/components/ui/StatTile";
import { Table, type TableColumn } from "@/components/ui/Table";
import { WebhooksTabsSection } from "@/components/views/WebhooksTabsSection";
import { formatDateTime, formatPercent, shortId } from "@/lib/format";

export interface WebhooksViewProps {
  endpoints: WebhookEndpointList;
  deliveries: WebhookDelivery[];
  deliverySuccessPct: number;
  source: "api" | "seed";
}

const ENDPOINT_COLUMNS: Array<TableColumn<WebhookEndpoint>> = [
  {
    key: "url",
    header: "Endpoint",
    render: (row) => (
      <span className="font-mono text-xs text-fg-muted">{row.url.replace(/^https:\/\//, "")}</span>
    ),
  },
  {
    key: "events",
    header: "Events",
    render: (row) => (
      <div className="flex flex-wrap gap-1">
        {row.events.slice(0, 3).map((event) => (
          <span
            key={event}
            className="rounded-pill bg-surface-2 px-2 py-0.5 font-mono text-[10px] text-fg-muted"
          >
            {event}
          </span>
        ))}
        {row.events.length > 3 ? (
          <span className="rounded-pill bg-surface-2 px-2 py-0.5 font-mono text-[10px] text-fg-subtle">
            +{row.events.length - 3}
          </span>
        ) : null}
      </div>
    ),
  },
  {
    key: "state",
    header: "State",
    render: (row) => <WebhookEndpointStateBadge state={row.state} />,
  },
  {
    key: "created_at",
    header: "Created",
    align: "right",
    render: (row) => <span className="text-xs text-fg-muted">{formatDateTime(row.created_at)}</span>,
  },
];

const DELIVERY_COLUMNS: Array<TableColumn<WebhookDelivery>> = [
  {
    key: "id",
    header: "Delivery",
    render: (row) => <span className="font-mono text-xs text-accent">{shortId(row.id, 10)}</span>,
  },
  {
    key: "event",
    header: "Event",
    render: (row) => (
      <span className="font-mono text-xs text-fg-muted">{row.event}</span>
    ),
  },
  {
    key: "subject",
    header: "Subject",
    render: (row) => (
      <span className="font-mono text-xs text-fg-muted">{shortId(row.subject, 10)}</span>
    ),
  },
  {
    key: "response_status",
    header: "HTTP",
    align: "right",
    render: (row) => (
      <span
        className={
          row.response_status === null
            ? "text-xs text-fg-subtle"
            : row.response_status >= 500 || row.response_status === 401
              ? "font-mono text-xs text-danger"
              : "font-mono text-xs text-success"
        }
      >
        {row.response_status ?? "—"}
      </span>
    ),
  },
  {
    key: "attempts",
    header: "Attempts",
    align: "right",
    render: (row) => <span className="font-mono text-xs tabular-nums">{row.attempts}</span>,
  },
  {
    key: "state",
    header: "State",
    render: (row) => <DeliveryStateBadge state={row.state} />,
  },
  {
    key: "last_attempted_at",
    header: "Last attempt",
    align: "right",
    render: (row) => (
      <span className="text-xs text-fg-muted">{formatDateTime(row.last_attempted_at)}</span>
    ),
  },
];

export function WebhooksView({
  endpoints,
  deliveries,
  deliverySuccessPct,
  source,
}: WebhooksViewProps) {
  const dead = endpoints.items.filter((hook) => hook.state === "dead").length;
  const retrying = deliveries.filter((delivery) => delivery.state === "retrying").length;

  return (
    <div className="space-y-6">
      <div className="grid gap-4 sm:grid-cols-3">
        <StatTile
          label="Delivery success"
          value={formatPercent(deliverySuccessPct)}
          tone={deliverySuccessPct >= 95 ? "success" : "warning"}
          hint="rolling 24h"
        />
        <StatTile
          label="Dead endpoints"
          value={dead}
          tone={dead > 0 ? "danger" : "success"}
          hint="auto-disabled after retries"
        />
        <StatTile label="Retrying now" value={retrying} tone="warning" hint="exponential backoff" />
      </div>

      <Card className="p-5">
        <div className="mb-4 flex items-center justify-between gap-3">
          <p className="text-sm text-fg-muted">
            Endpoints and their delivery log — HMAC-SHA256 signed, CloudEvents 1.0 envelopes,
            dedupe on the event id.
          </p>
          <DataOrigin source={source} />
        </div>
        <WebhooksTabsSection
          counts={{ endpoints: endpoints.items.length, deliveries: deliveries.length }}
          endpoints={
            <div className="rounded-card border border-border-subtle">
              <Table
                caption="Webhook endpoints"
                columns={ENDPOINT_COLUMNS}
                rows={endpoints.items}
                rowKey={(row) => row.id}
                empty={
                  <EmptyState
                    icon="webhooks"
                    title="No webhook endpoints registered"
                    description="Register an HTTPS endpoint to receive payment.*, payout.*, wallet.* and risk.* events."
                  />
                }
              />
            </div>
          }
          deliveries={
            <div className="rounded-card border border-border-subtle">
              <Table
                caption="Recent webhook deliveries"
                columns={DELIVERY_COLUMNS}
                rows={deliveries}
                rowKey={(row) => row.id}
                empty="No deliveries in the window."
              />
            </div>
          }
        />
      </Card>
    </div>
  );
}
