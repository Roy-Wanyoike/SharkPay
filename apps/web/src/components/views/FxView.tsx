import type { Conversion, FxQuote } from "@/lib/api/sdk/fx";
import { DataOrigin } from "@/components/shared/DataOrigin";
import { QuoteStateBadge } from "@/components/shared/StateBadges";
import { Card, CardHeader } from "@/components/ui/Card";
import { StatTile } from "@/components/ui/StatTile";
import { Table, type TableColumn } from "@/components/ui/Table";
import { NewQuoteDialog } from "@/components/views/NewQuoteDialog";
import { formatDateTime, shortId } from "@/lib/format";
import { formatMoney, formatRate } from "@/lib/money";

export interface FxViewProps {
  quotes: FxQuote[];
  conversions: Conversion[];
  source: "api" | "seed";
  markupBps: number;
}

const QUOTE_COLUMNS: Array<TableColumn<FxQuote>> = [
  {
    key: "id",
    header: "Quote",
    render: (row) => <span className="font-mono text-xs text-accent">{shortId(row.id, 10)}</span>,
  },
  {
    key: "pair",
    header: "Pair",
    render: (row) => (
      <span className="font-mono text-xs text-fg">
        {row.base_currency}/{row.quote_currency}
      </span>
    ),
  },
  {
    key: "rate",
    header: "Rate",
    align: "right",
    render: (row) => (
      <span className="font-mono text-xs tabular-nums text-fg-muted">{formatRate(row.rate)}</span>
    ),
  },
  {
    key: "source_amount",
    header: "Source",
    align: "right",
    render: (row) => (
      <span className="font-mono text-xs tabular-nums">{formatMoney(row.source_amount)}</span>
    ),
  },
  {
    key: "target_amount",
    header: "Target (indicative)",
    align: "right",
    render: (row) => (
      <span className="font-mono text-xs tabular-nums">{formatMoney(row.target_amount)}</span>
    ),
  },
  {
    key: "state",
    header: "State",
    render: (row) => <QuoteStateBadge state={row.state} />,
  },
  {
    key: "expires_at",
    header: "Expires",
    align: "right",
    render: (row) => <span className="text-xs text-fg-muted">{formatDateTime(row.expires_at)}</span>,
  },
];

const CONVERSION_COLUMNS: Array<TableColumn<Conversion>> = [
  {
    key: "id",
    header: "Conversion",
    render: (row) => <span className="font-mono text-xs text-accent">{shortId(row.id, 10)}</span>,
  },
  {
    key: "pair",
    header: "Pair",
    render: (row) => (
      <span className="font-mono text-xs text-fg">
        {row.source_amount.currency}/{row.target_amount.currency}
      </span>
    ),
  },
  {
    key: "source_amount",
    header: "Source",
    align: "right",
    render: (row) => (
      <span className="font-mono text-xs tabular-nums">{formatMoney(row.source_amount)}</span>
    ),
  },
  {
    key: "target_amount",
    header: "Target",
    align: "right",
    render: (row) => (
      <span className="font-mono text-xs tabular-nums">{formatMoney(row.target_amount)}</span>
    ),
  },
  {
    key: "entry_id",
    header: "Ledger entry",
    render: (row) => <span className="font-mono text-xs text-fg-muted">{shortId(row.entry_id, 8)}</span>,
  },
  {
    key: "created_at",
    header: "Executed",
    align: "right",
    render: (row) => <span className="text-xs text-fg-muted">{formatDateTime(row.created_at)}</span>,
  },
];

export function FxView({ quotes, conversions, source, markupBps }: FxViewProps) {
  const executed = conversions.length;
  const live = source === "api";

  return (
    <div className="space-y-6">
      <div className="grid gap-4 sm:grid-cols-3">
        <StatTile
          label="Quotes (24h)"
          value={quotes.length}
          hint={`${quotes.filter((quote) => quote.state === "QUOTED").length} still live`}
        />
        <StatTile
          label="Conversions executed"
          value={executed}
          tone="accent"
          hint="4-leg ledger postings"
        />
        <StatTile
          label="Mark-up policy"
          value={`${(markupBps / 100).toFixed(2)}%`}
          hint={`${markupBps} bps on mid-rate`}
        />
      </div>

      <Card>
        <CardHeader
          title="Recent quotes"
          description="TTL'd indicative quotes — expiry of a LOCKED quote pages ops (p1)."
          actions={
            <div className="flex items-center gap-2">
              <DataOrigin source={source} />
              <NewQuoteDialog live={live} />
            </div>
          }
        />
        <Table
          caption="FX quotes"
          columns={QUOTE_COLUMNS}
          rows={quotes}
          rowKey={(row) => row.id}
          empty="No quotes requested in the window."
        />
      </Card>

      <Card>
        <CardHeader
          title="Recent conversions"
          description="Executed wallet-to-wallet conversions at their locked rates."
        />
        <Table
          caption="FX conversions"
          columns={CONVERSION_COLUMNS}
          rows={conversions}
          rowKey={(row) => row.id}
          empty="No conversions executed in the window."
        />
      </Card>
    </div>
  );
}
