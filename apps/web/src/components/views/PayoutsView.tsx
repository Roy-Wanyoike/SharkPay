import type { Payout, PayoutList, PayoutDestination } from "@/lib/api/sdk/payouts";
import { DataOrigin } from "@/components/shared/DataOrigin";
import { PayoutStateBadge } from "@/components/shared/StateBadges";
import { Badge } from "@/components/ui/Badge";
import { Card } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/EmptyState";
import { Select } from "@/components/ui/Select";
import { Table, type TableColumn } from "@/components/ui/Table";
import { formatDateTime, maskTail, shortId } from "@/lib/format";
import { formatMoney } from "@/lib/money";

export interface PayoutsViewProps {
  page: PayoutList;
  source: "api" | "seed";
  filters: { state?: string; rail?: string };
}

const PAYOUT_STATES = [
  "CREATED",
  "PENDING_RISK",
  "PROCESSING",
  "SENT",
  "SUCCEEDED",
  "FAILED",
  "RETURNED",
  "BLOCKED",
  "CANCELLED",
] as const;

const PAYOUT_RAILS = ["mpesa", "bank", "on_chain"] as const;

function describeDestination(destination: PayoutDestination): string {
  switch (destination.type) {
    case "mpesa":
      return `M-Pesa ${maskTail(destination.msisdn, 4)}`;
    case "bank":
      return `${destination.bank_code} ••${destination.account_number.slice(-4)}`;
    case "on_chain":
      return `${destination.network} ${shortId(destination.address, 6)}`;
  }
}

const COLUMNS: Array<TableColumn<Payout>> = [
  {
    key: "id",
    header: "Payout",
    render: (row) => <span className="font-mono text-xs text-accent">{shortId(row.id, 12)}</span>,
  },
  {
    key: "state",
    header: "State",
    render: (row) => <PayoutStateBadge state={row.state} />,
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
      <span className="font-mono text-xs text-fg-muted tabular-nums">{formatMoney(row.fee)}</span>
    ),
  },
  { key: "rail", header: "Rail", render: (row) => <Badge variant="outline">{row.rail}</Badge> },
  {
    key: "destination",
    header: "Destination",
    render: (row) => (
      <span className="text-xs text-fg-muted">{describeDestination(row.destination)}</span>
    ),
  },
  {
    key: "source_wallet",
    header: "Source wallet",
    render: (row) => (
      <span className="font-mono text-xs text-fg-muted">{shortId(row.source_wallet, 10)}</span>
    ),
  },
  {
    key: "created_at",
    header: "Created",
    align: "right",
    render: (row) => <span className="text-xs text-fg-muted">{formatDateTime(row.created_at)}</span>,
  },
];

export function PayoutsView({ page, source, filters }: PayoutsViewProps) {
  return (
    <div className="space-y-4">
      <Card>
        <form
          method="get"
          action="/payouts"
          className="flex flex-wrap items-end gap-3 px-5 py-4"
          aria-label="Filter payouts"
        >
          <div className="min-w-44">
            <Select label="State" name="state" defaultValue={filters.state ?? ""}>
              <option value="">All states</option>
              {PAYOUT_STATES.map((state) => (
                <option key={state} value={state}>
                  {state}
                </option>
              ))}
            </Select>
          </div>
          <div className="min-w-40">
            <Select label="Rail" name="rail" defaultValue={filters.rail ?? ""}>
              <option value="">All rails</option>
              {PAYOUT_RAILS.map((rail) => (
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
                href="/payouts"
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
          caption="Payouts"
          columns={COLUMNS}
          rows={page.items}
          rowKey={(row) => row.id}
          empty={
            <EmptyState
              icon="payouts"
              title="No payouts match"
              description="Try clearing the filters. Returned payouts re-credit the wallet minus non-refundable rail fees."
            />
          }
        />
      </Card>
    </div>
  );
}
