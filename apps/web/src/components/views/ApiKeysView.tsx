import type { ApiKey, ApiKeyList } from "@/lib/api/sdk/apikeys";
import { DataOrigin } from "@/components/shared/DataOrigin";
import { ApiKeyStateBadge } from "@/components/shared/StateBadges";
import { Badge } from "@/components/ui/Badge";
import { Card, CardHeader } from "@/components/ui/Card";
import { Table, type TableColumn } from "@/components/ui/Table";
import { NewApiKeyDialog } from "@/components/views/NewApiKeyDialog";
import { formatDateTime } from "@/lib/format";

export interface ApiKeysViewProps {
  page: ApiKeyList;
  source: "api" | "seed";
}

const COLUMNS: Array<TableColumn<ApiKey>> = [
  {
    key: "name",
    header: "Name",
    render: (row) => <span className="text-sm font-medium text-fg">{row.name}</span>,
  },
  {
    key: "masked_key",
    header: "Key",
    render: (row) => (
      <span className="font-mono text-xs text-fg-muted">{row.masked_key}</span>
    ),
  },
  {
    key: "environment",
    header: "Env",
    render: (row) => (
      <Badge variant={row.environment === "live" ? "warning" : "neutral"} mono>
        {row.environment}
      </Badge>
    ),
  },
  {
    key: "scopes",
    header: "Scopes",
    render: (row) => (
      <div className="flex flex-wrap gap-1">
        {row.scopes.slice(0, 3).map((scope) => (
          <span
            key={scope}
            className="rounded-pill bg-surface-2 px-2 py-0.5 font-mono text-[10px] text-fg-muted"
          >
            {scope}
          </span>
        ))}
        {row.scopes.length > 3 ? (
          <span className="rounded-pill bg-surface-2 px-2 py-0.5 font-mono text-[10px] text-fg-subtle">
            +{row.scopes.length - 3}
          </span>
        ) : null}
      </div>
    ),
  },
  {
    key: "state",
    header: "State",
    render: (row) => <ApiKeyStateBadge state={row.state} />,
  },
  {
    key: "last_used_at",
    header: "Last used",
    align: "right",
    render: (row) => (
      <span className="text-xs text-fg-muted">
        {row.last_used_at ? formatDateTime(row.last_used_at) : "never"}
      </span>
    ),
  },
  {
    key: "created_at",
    header: "Created",
    align: "right",
    render: (row) => <span className="text-xs text-fg-muted">{formatDateTime(row.created_at)}</span>,
  },
];

export function ApiKeysView({ page, source }: ApiKeysViewProps) {
  return (
    <Card>
      <CardHeader
        title="API keys"
        description="Scoped programmatic access (docs/API-CONTRACTS.md §5). Secrets are write-only: visible exactly once at creation, masked everywhere else."
        actions={
          <div className="flex items-center gap-2">
            <DataOrigin source={source} />
            <NewApiKeyDialog />
          </div>
        }
      />
      <Table
        caption="API keys"
        columns={COLUMNS}
        rows={page.items}
        rowKey={(row) => row.id}
        empty="No API keys provisioned yet."
      />
    </Card>
  );
}
