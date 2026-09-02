import type { HTMLAttributes, ReactNode } from "react";
import { cn } from "@/lib/utils";

export type BadgeVariant =
  | "neutral"
  | "outline"
  | "success"
  | "warning"
  | "danger"
  | "info"
  | "accent";

const VARIANT_CLASSES: Record<BadgeVariant, string> = {
  neutral: "bg-surface-2 text-fg-muted border border-border-subtle",
  outline: "bg-transparent text-fg-muted border border-border-strong",
  success: "bg-success-soft text-success border border-transparent",
  warning: "bg-warning-soft text-warning border border-transparent",
  danger: "bg-danger-soft text-danger border border-transparent",
  info: "bg-info-soft text-info border border-transparent",
  accent: "bg-accent-soft text-accent border border-accent-border",
};

export interface BadgeProps extends HTMLAttributes<HTMLSpanElement> {
  variant?: BadgeVariant;
  /** Leading dot marker. */
  dot?: boolean;
  /** Renders in uppercase mono with tracking (ids, codes). */
  mono?: boolean;
  children: ReactNode;
}

export function Badge({
  variant = "neutral",
  dot = false,
  mono = false,
  className,
  children,
  ...rest
}: BadgeProps) {
  return (
    <span
      className={cn(
        "inline-flex items-center gap-1.5 rounded-pill px-2 py-0.5 text-xs font-medium whitespace-nowrap",
        mono && "font-mono tracking-wide uppercase",
        VARIANT_CLASSES[variant],
        className,
      )}
      {...rest}
    >
      {dot ? <span aria-hidden="true" className="h-1.5 w-1.5 rounded-full bg-current" /> : null}
      {children}
    </span>
  );
}
