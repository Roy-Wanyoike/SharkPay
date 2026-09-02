import type { ReactNode } from "react";
import { Icon, type IconName } from "@/components/layout/icons";
import { cn } from "@/lib/utils";

export interface EmptyStateProps {
  title: string;
  description?: string;
  icon?: IconName;
  /** Call-to-action slot (typically a Button). */
  action?: ReactNode;
  className?: string;
}

export function EmptyState({
  title,
  description,
  icon = "info",
  action,
  className,
}: EmptyStateProps) {
  return (
    <div
      className={cn(
        "flex flex-col items-center justify-center gap-3 px-6 py-12 text-center",
        className,
      )}
    >
      <span className="flex h-12 w-12 items-center justify-center rounded-full bg-surface-2 text-fg-subtle">
        <Icon name={icon} size={24} />
      </span>
      <div className="space-y-1">
        <p className="text-sm font-semibold text-fg">{title}</p>
        {description ? (
          <p className="mx-auto max-w-sm text-xs text-fg-muted">{description}</p>
        ) : null}
      </div>
      {action ? <div className="mt-1">{action}</div> : null}
    </div>
  );
}
