/** Presentation helpers for dates, percentages and ids (non-money). */

const DATE_FORMAT = new Intl.DateTimeFormat("en-GB", {
  day: "2-digit",
  month: "short",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
  timeZone: "UTC",
});

const DATE_FULL_FORMAT = new Intl.DateTimeFormat("en-GB", {
  day: "numeric",
  month: "short",
  year: "numeric",
  hour: "2-digit",
  minute: "2-digit",
  hour12: false,
  timeZone: "UTC",
});

function toDate(iso: string): Date | null {
  const date = new Date(iso);
  return Number.isNaN(date.getTime()) ? null : date;
}

/** "02 Sep, 14:32 UTC" — the console intentionally shows UTC everywhere. */
export function formatDateTime(iso: string): string {
  const date = toDate(iso);
  return date ? `${DATE_FORMAT.format(date)} UTC` : "—";
}

/** "2 Sep 2026, 14:32 UTC". */
export function formatDateTimeFull(iso: string): string {
  const date = toDate(iso);
  return date ? `${DATE_FULL_FORMAT.format(date)} UTC` : "—";
}

export function formatPercent(value: number, digits = 1): string {
  return `${value.toFixed(digits)}%`;
}

/** Shortens long opaque ids for table cells, e.g. "pay_01HZ…". */
export function shortId(id: string, keep = 8): string {
  return id.length <= keep + 1 ? id : `${id.slice(0, keep)}…`;
}

/** Masks a value for display, keeping only the last `visible` characters. */
export function maskTail(value: string, visible = 4): string {
  if (value.length <= visible) return value;
  return `${"•".repeat(Math.min(8, value.length - visible))}${value.slice(-visible)}`;
}
