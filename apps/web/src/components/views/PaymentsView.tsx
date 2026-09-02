import type { Payment, PaymentList, PaymentState, Rail } from "@/lib/api/sdk/payments";
import { DataOrigin } from "@/components/shared/DataOrigin";
import { PaymentStateBadge } from "@/components/shared/StateBadges";
import { Badge } from "@/components/ui/Badge";
import { Card } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/EmptyState";
import { Select } from "@/components/ui/Select";
import { Table, type TableColumn } from "@/components/ui/Table";
import { formatDateTime, shortId } from "@/lib/format";
import { formatMoney } from "@/lib/money";

export interface PaymentFilters {
  state?: string;
  rail?: string;
}

export interface PaymentsViewProps {
  page: PaymentList;
  source: "api" | "seed";
  filters: PaymentFilters;
  nextCursor?: string | null;
}

const PAYMENT_STATES: PaymentState[] = [
  "CREATED",
  "PENDING_PROVIDER",
  "PROCESSING",
  "SUCCEEDED",
  "FAILED",
  "EXPIRED",
  "REVERSED",
  "BLOCKED",
  "CANCELLED",
];

const RAILS: Rail[] = ["honeycoin", "mpesa", "bank", "on_chain"];

const COLUMNS: Array<TableColumn<Payment>> = [
  {
    key: "id",
    header: "Intent",
    render: (row) => (
      <span className="font-mono text-xs text-accent">{shortId(row.id, 12)}</span>
    ),
  },
  {
    key: "state",
    header: "State",
    render: (row) => <PaymentStateBadge state={row.state} />,
  },
  {
    key: "amount",
    header: "Amount",
    align: "right",
    render: (row) => (
      <span className="font-mono text-xs tabular-nums">{formatMoney(row.amount)}</span>
    ),
  },
  {
    key: "fee",
    header: "Fee",
    align: "right",
    render: (row) => (
      <span className="font-mono text-xs text-fg-muted tabular-nums">
        {formatMoney(row.fee)}
      </span>
    ),
  },
  { key: "rail", header: "Rail", render: (row) => <Badge variant="outline">{row.rail}</Badge> },
  {
    key: "destination_wallet",
    header: "Destination wallet",
    render: (row) => (
      <span className="font-mono text-xs text-fg-muted">{shortId(row.destination_wallet, 10)}</span>
    ),
  },
  {
    key: "created_at",
    header: "Created",
    align: "right",
    render: (row) => <span className="text-xs text-fg-muted">{formatDateTime(row.created_at)}</span>,
  },
];

function buildQuery(filters: PaymentFilters, cursor?: string | null): string {
  const params = new URLSearchParams();
  if (filters.state) params.set("state", filters.state);
  if (filters.rail) params.set("rail", filters.rail);
  if (cursor) params.set("cursor", cursor);
  const query = params.toString();
  return query ? `?${query}` : "";
}

export function PaymentsView({ page, source, filters, nextCursor }: PaymentsViewProps) {
  return (
    <div className="space-y-4">
      <Card>
        <form
          method="get"
          action="/payments"
          className="flex flex-wrap items-end gap-3 px-5 py-4"
          aria-label="Filter payments"
        >
          <div className="min-w-44">
            <Select label="State" name="state" defaultValue={filters.state ?? ""}>
              <option value="">All states</option>
              {PAYMENT_STATES.map((state) => (
                <option key={state} value={state}>
                  {state}
                </option>
              ))}
            </Select>
          </div>
          <div className="min-w-40">
            <Select label="Rail" name="rail" defaultValue={filters.rail ?? ""}>
              <option value="">All rails</option>
              {RAILS.map((rail) => (
                <option key={rail} value={rail}>
                  {rail}
                </option>
              ))}
            </Select>
          </div>
          <div className="flex items-center gap-2">
            <button
              type="submit"
              className="inline-flex h-10 items-center rounded-lg bg-accent px-4 text-sm font-medium text-accent-fg hover:bg-accent-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
            >
              Apply filters
            </button>
            {filters.state || filters.rail ? (
              <a
                href="/payments"
                className="inline-flex h-10 items-center rounded-lg border border-border-subtle px-3 text-sm text-fg-muted hover:bg-surface-2 hover:text-fg focus-visible:outline-2 focus-visible:outline-accent"
              >
                Reset
              </a>
            ) : null}
          </div>
          <div className="ml-auto">
            <DataOrigin source={source} />
          </div>
        </form>
      </Card>

      <Card>
        <Table
          caption="Payment intents"
          columns={COLUMNS}
          rows={page.items}
          rowKey={(row) => row.id}
          empty={
            <EmptyState
              icon="payments"
              title="No payment intents match"
              description="Try clearing the state or rail filters — the list also reflects the active API/seed source."
            />
          }
        />
        {nextCursor ? (
          <div className="flex items-center justify-end gap-3 border-t border-border-subtle px-5 py-3">
            <span className="text-xs text-fg-subtle">
              Showing {page.items.length} intents
            </span>
            <a
              href={`/payments${buildQuery(filters, nextCursor)}`}
              className="inline-flex h-8 items-center gap-1 rounded-lg border border-border-subtle px-3 text-xs font-medium text-fg hover:bg-surface-2 focus-visible:outline-2 focus-visible:outline-accent"
            >
              Next page
            </a>
          </div>
        ) : (
          <div className="flex items-center justify-end border-t border-border-subtle px-5 py-3">
            <span className="text-xs text-fg-subtle">
              {page.items.length} intent{page.items.length === 1 ? "" : "s"} · end of list
            </span>
          </div>
        )}
      </Card>
    </div>
  );
}
