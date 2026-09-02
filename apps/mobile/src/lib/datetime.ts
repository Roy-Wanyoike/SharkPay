/**
 * Timestamp rendering without Intl reliance (Hermes Intl availability is not
 * guaranteed on every target); pure and unit-testable.
 */

const MONTHS = [
  'Jan',
  'Feb',
  'Mar',
  'Apr',
  'May',
  'Jun',
  'Jul',
  'Aug',
  'Sep',
  'Oct',
  'Nov',
  'Dec',
] as const;

function pad2(value: number): string {
  return value < 10 ? `0${value}` : String(value);
}

/**
 * Renders an RFC 3339 timestamp as "2 Sep 2026, 14:32" (local time).
 * Returns "—" for unparseable input rather than throwing.
 */
export function formatTimestamp(iso: string): string {
  const date = new Date(iso);
  if (Number.isNaN(date.getTime())) {
    return '—';
  }
  return `${date.getDate()} ${MONTHS[date.getMonth()]} ${date.getFullYear()}, ${pad2(
    date.getHours(),
  )}:${pad2(date.getMinutes())}`;
}

/** Renders an epoch-ms instant the same way (session expiry etc.). */
export function formatEpochMs(epochMs: number): string {
  if (!Number.isFinite(epochMs)) {
    return '—';
  }
  const date = new Date(epochMs);
  return `${date.getDate()} ${MONTHS[date.getMonth()]} ${date.getFullYear()}, ${pad2(
    date.getHours(),
  )}:${pad2(date.getMinutes())}`;
}
