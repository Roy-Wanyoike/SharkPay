import type { ReactNode } from "react";
import { cn } from "@/lib/utils";

export interface TableColumn<Row> {
  /** Stable key (row field or arbitrary). */
  key: string;
  header: ReactNode;
  align?: "left" | "right" | "center";
  /** Cell renderer; defaults to the row's string/number value at `key`. */
  render?: (row: Row) => ReactNode;
  className?: string;
}

export interface TableProps<Row> {
  columns: Array<TableColumn<Row>>;
  rows: readonly Row[];
  /** Stable unique id per row (rendered as data-row-id). */
  rowKey: (row: Row) => string;
  /** Rendered instead of tbody when rows is empty. */
  empty?: ReactNode;
  /** Visually hidden caption for screen readers. */
  caption?: string;
  dense?: boolean;
  className?: string;
}

const ALIGN_CLASSES = {
  left: "text-left",
  right: "text-right",
  center: "text-center",
} as const;

function defaultCell<Row>(row: Row, key: string): ReactNode {
  const value = (row as Record<string, unknown>)[key];
  if (typeof value === "string" || typeof value === "number") {
    return value;
  }
  if (value === null || value === undefined) {
    return "—";
  }
  return null;
}

export function Table<Row>({
  columns,
  rows,
  rowKey,
  empty,
  caption,
  dense = false,
  className,
}: TableProps<Row>) {
  const hasRows = rows.length > 0;
  return (
    <div className={cn("w-full overflow-x-auto", className)}>
      <table className="w-full border-collapse text-sm">
        {caption ? <caption className="sr-only">{caption}</caption> : null}
        <thead>
          <tr className="border-b border-border-subtle">
            {columns.map((column) => (
              <th
                key={column.key}
                scope="col"
                className={cn(
                  "bg-surface px-4 py-2.5 text-xs font-semibold tracking-wide text-fg-muted uppercase",
                  dense && "py-2",
                  ALIGN_CLASSES[column.align ?? "left"],
                  column.className,
                )}
              >
                {column.header}
              </th>
            ))}
          </tr>
        </thead>
        {hasRows ? (
          <tbody>
            {rows.map((row) => (
              <tr
                key={rowKey(row)}
                data-row-id={rowKey(row)}
                className="border-b border-border-subtle/60 transition-colors last:border-b-0 hover:bg-surface-2/60"
              >
                {columns.map((column) => (
                  <td
                    key={column.key}
                    className={cn(
                      "px-4 py-3 align-middle text-fg",
                      dense && "py-2",
                      ALIGN_CLASSES[column.align ?? "left"],
                      column.className,
                    )}
                  >
                    {column.render
                      ? column.render(row)
                      : defaultCell(row, column.key)}
                  </td>
                ))}
              </tr>
            ))}
          </tbody>
        ) : null}
      </table>
      {!hasRows ? <div className="px-4 py-8 text-center text-sm text-fg-muted">{empty ?? "No results."}</div> : null}
    </div>
  );
}
