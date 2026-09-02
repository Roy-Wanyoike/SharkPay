import type { RiskCase, RiskCaseList } from "@/lib/api/sdk/risk";
import { DataOrigin } from "@/components/shared/DataOrigin";
import { RiskCaseStateBadge, RiskSeverityBadge } from "@/components/shared/StateBadges";
import { Card } from "@/components/ui/Card";
import { EmptyState } from "@/components/ui/EmptyState";
import { Select } from "@/components/ui/Select";
import { StatTile } from "@/components/ui/StatTile";
import { Table, type TableColumn } from "@/components/ui/Table";
import { formatDateTime, shortId } from "@/lib/format";
import { formatMoney } from "@/lib/money";

export interface RiskViewProps {
  page: RiskCaseList;
  source: "api" | "seed";
  filters: { state?: string };
}

const STATES = ["OPEN", "INVESTIGATING", "RESOLVED", "DISMISSED"] as const;

const COLUMNS: Array<TableColumn<RiskCase>> = [
  {
    key: "id",
    header: "Case",
    render: (row) => <span className="font-mono text-xs text-accent">{shortId(row.id, 10)}</span>,
  },
  {
    key: "state",
    header: "State",
    render: (row) => <RiskCaseStateBadge state={row.state} />,
  },
  {
    key: "severity",
    header: "Severity",
    render: (row) => <RiskSeverityBadge severity={row.severity} />,
  },
  {
    key: "rule",
    header: "Rule",
    render: (row) => (
      <span className="font-mono text-xs text-fg-muted">{row.rule}</span>
    ),
  },
  {
    key: "subject_ref",
    header: "Subject",
    render: (row) => (
      <span className="font-mono text-xs text-fg-muted">{shortId(row.subject_ref, 10)}</span>
    ),
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
    key: "assignee",
    header: "Assignee",
    render: (row) => (
      <span className="text-xs text-fg-muted">{row.assignee ?? "unassigned"}</span>
    ),
  },
  {
    key: "opened_at",
    header: "Opened",
    align: "right",
    render: (row) => <span className="text-xs text-fg-muted">{formatDateTime(row.opened_at)}</span>,
  },
];

export function RiskView({ page, source, filters }: RiskViewProps) {
  const open = page.items.filter((riskCase) => riskCase.state === "OPEN");
  const investigating = page.items.filter((riskCase) => riskCase.state === "INVESTIGATING");
  const critical = page.items.filter(
    (riskCase) => riskCase.severity === "critical" && riskCase.state !== "RESOLVED" && riskCase.state !== "DISMISSED",
  );

  return (
    <div className="space-y-4">
      <div className="grid gap-4 sm:grid-cols-3">
        <StatTile label="Open cases" value={open.length} tone={open.length > 0 ? "warning" : "success"} hint="awaiting triage" />
        <StatTile label="In investigation" value={investigating.length} hint="assigned operators" />
        <StatTile
          label="Unresolved critical"
          value={critical.length}
          tone={critical.length > 0 ? "danger" : "success"}
          hint="SLA: 2h to assign"
        />
      </div>

      <Card>
        <form
          method="get"
          action="/risk"
          className="flex flex-wrap items-end gap-3 px-5 py-4"
          aria-label="Filter risk cases"
        >
          <div className="min-w-44">
            <Select label="State" name="state" defaultValue={filters.state ?? ""}>
              <option value="">All states</option>
              {STATES.map((state) => (
                <option key={state} value={state}>
                  {state}
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
            {filters.state ? (
              <a
                href="/risk"
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
          caption="Risk cases"
          columns={COLUMNS}
          rows={page.items}
          rowKey={(row) => row.id}
          empty={
            <EmptyState
              icon="risk"
              title="No risk cases match"
              description="Rule hits from the risk service land here for triage — try clearing the state filter."
            />
          }
        />
      </Card>
    </div>
  );
}
