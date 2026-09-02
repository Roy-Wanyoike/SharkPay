import type { Wallet, WalletList } from "@/lib/api/sdk/wallets";
import { DataOrigin } from "@/components/shared/DataOrigin";
import { WalletStatusBadge } from "@/components/shared/StateBadges";
import { Badge } from "@/components/ui/Badge";
import { Card } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/EmptyState";
import { Select } from "@/components/ui/Select";
import { StatTile } from "@/components/ui/StatTile";
import { Table, type TableColumn } from "@/components/ui/Table";
import { formatDateTime } from "@/lib/format";
import { formatMoney, formatMinor } from "@/lib/money";

export interface WalletsViewProps {
  page: WalletList;
  source: "api" | "seed";
  filters: { currency?: string; status?: string };
}

const CURRENCIES = ["KES", "USD", "EUR", "GBP", "USDC", "USDT"] as const;
const STATUSES = ["active", "frozen", "closed"] as const;

const COLUMNS: Array<TableColumn<Wallet>> = [
  {
    key: "id",
    header: "Wallet",
    render: (row) => <span className="font-mono text-xs text-accent">{row.id.slice(0, 14)}…</span>,
  },
  {
    key: "currency",
    header: "Currency",
    render: (row) => <Badge variant="accent" mono>{row.currency}</Badge>,
  },
  {
    key: "status",
    header: "Status",
    render: (row) => <WalletStatusBadge status={row.status} />,
  },
  {
    key: "available",
    header: "Available",
    align: "right",
    render: (row) => (
      <span className="font-mono text-xs tabular-nums">{formatMoney(row.balances.available)}</span>
    ),
  },
  {
    key: "pending",
    header: "Pending",
    align: "right",
    render: (row) => (
      <span className="font-mono text-xs text-fg-muted tabular-nums">
        {formatMoney(row.balances.pending)}
      </span>
    ),
  },
  {
    key: "held",
    header: "Held",
    align: "right",
    render: (row) => (
      <span className="font-mono text-xs text-fg-muted tabular-nums">
        {formatMoney(row.balances.held)}
      </span>
    ),
  },
  {
    key: "created_at",
    header: "Opened",
    align: "right",
    render: (row) => <span className="text-xs text-fg-muted">{formatDateTime(row.created_at)}</span>,
  },
];

export function WalletsView({ page, source, filters }: WalletsViewProps) {
  const active = page.items.filter((wallet) => wallet.status === "active");
  const frozen = page.items.filter((wallet) => wallet.status === "frozen");
  const totalHeldMinor = page.items.reduce(
    (sum, wallet) =>
      wallet.balances.held.currency === "KES" ? sum + wallet.balances.held.amount_minor : sum,
    0,
  );

  return (
    <div className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-3">
        <StatTile label="Active wallets" value={active.length} hint="across all currencies" />
        <StatTile
          label="Frozen wallets"
          value={frozen.length}
          tone={frozen.length > 0 ? "warning" : "success"}
          hint="compliance freeze"
        />
        <StatTile
          label="Held (KES)"
          value={formatMinor(totalHeldMinor, 2)}
          hint="reserved by in-flight ops"
          tone="accent"
        />
      </div>

      <Card>
        <form
          method="get"
          action="/wallets"
          className="flex flex-wrap items-end gap-3 px-5 py-4"
          aria-label="Filter wallets"
        >
          <div className="min-w-36">
            <Select label="Currency" name="currency" defaultValue={filters.currency ?? ""}>
              <option value="">All currencies</option>
              {CURRENCIES.map((currency) => (
                <option key={currency} value={currency}>
                  {currency}
                </option>
              ))}
            </Select>
          </div>
          <div className="min-w-36">
            <Select label="Status" name="status" defaultValue={filters.status ?? ""}>
              <option value="">All statuses</option>
              {STATUSES.map((status) => (
                <option key={status} value={status}>
                  {status}
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
            {filters.currency || filters.status ? (
              <a
                href="/wallets"
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
          caption="Wallets"
          columns={COLUMNS}
          rows={page.items}
          rowKey={(row) => row.id}
          empty={
            <EmptyState
              icon="wallets"
              title="No wallets match"
              description="One wallet per principal per currency — try clearing the currency or status filter."
            />
          }
        />
      </Card>
    </div>
  );
}
