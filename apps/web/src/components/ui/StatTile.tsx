import type { ReactNode } from "react";
import { Icon } from "@/components/layout/icons";
import { cn } from "@/lib/utils";

export type StatTileTone = "default" | "success" | "warning" | "danger" | "accent";

export interface StatTileDelta {
  direction: "up" | "down" | "flat";
  /** Short copy, e.g. "+12.4% vs yesterday". */
  text: string;
}

export interface StatTileProps {
  label: string;
  value: ReactNode;
  hint?: string;
  delta?: StatTileDelta;
  tone?: StatTileTone;
  icon?: ReactNode;
  className?: string;
}

const TONE_VALUE_CLASSES: Record<StatTileTone, string> = {
  default: "text-fg",
  success: "text-success",
  warning: "text-warning",
  danger: "text-danger",
  accent: "text-accent",
};

const DELTA_CLASSES: Record<StatTileDelta["direction"], string> = {
  up: "text-success",
  down: "text-danger",
  flat: "text-fg-muted",
};

const DELTA_ICONS: Record<StatTileDelta["direction"], "chevron-down" | "chevron-right" | undefined> = {
  up: undefined,
  down: "chevron-down",
  flat: "chevron-right",
};

export function StatTile({
  label,
  value,
  hint,
  delta,
  tone = "default",
  icon,
  className,
}: StatTileProps) {
  return (
    <div
      className={cn(
        "rounded-card border border-border-subtle bg-surface p-5 transition-colors hover:border-border-strong",
        className,
      )}
    >
      <div className="flex items-center justify-between gap-3">
        <p className="text-xs font-medium tracking-wide text-fg-muted uppercase">{label}</p>
        {icon ? <span className="text-fg-subtle">{icon}</span> : null}
      </div>
      <p className={cn("mt-2 font-mono text-2xl font-semibold tabular-nums", TONE_VALUE_CLASSES[tone])}>
        {value}
      </p>
      {(delta ?? hint) && (
        <p className="mt-1 flex items-center gap-1.5 text-xs text-fg-muted">
          {delta ? (
            <span
              className={cn(
                "inline-flex items-center gap-0.5 font-medium",
                DELTA_CLASSES[delta.direction],
              )}
            >
              {delta.direction === "up" ? (
                <svg
                  width="12"
                  height="12"
                  viewBox="0 0 24 24"
                  fill="none"
                  stroke="currentColor"
                  strokeWidth="2.5"
                  aria-hidden="true"
                >
                  <path d="m6 15 6-6 6 6" strokeLinecap="round" strokeLinejoin="round" />
                </svg>
              ) : (
                <Icon name={DELTA_ICONS[delta.direction] ?? "chevron-right"} size={12} />
              )}
              {delta.text}
            </span>
          ) : null}
          {hint ? <span className="truncate">{hint}</span> : null}
        </p>
      )}
    </div>
  );
}
