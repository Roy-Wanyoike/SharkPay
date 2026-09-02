"use client";

import { useId, type ReactNode, type SelectHTMLAttributes } from "react";
import { Icon } from "@/components/layout/icons";
import { cn } from "@/lib/utils";

export interface SelectProps
  extends Omit<SelectHTMLAttributes<HTMLSelectElement>, "id" | "children"> {
  /** Visible label (always rendered). */
  label: string;
  /** Option elements. */
  children: ReactNode;
  hint?: string;
}

/**
 * Native select styled to the design system — native semantics give us the
 * full keyboard/AT story for free (ARIA comboboxes are easy to break).
 */
export function Select({ label, children, hint, className, ...rest }: SelectProps) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="block text-xs font-medium text-fg-muted">
        {label}
      </label>
      <div className="relative">
        <select
          id={id}
          aria-describedby={hintId}
          className={cn(
            "h-10 w-full appearance-none rounded-lg border border-border-subtle bg-surface-2 pl-3 pr-9 text-sm text-fg",
            "focus:outline-none focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent",
            className,
          )}
          {...rest}
        >
          {children}
        </select>
        <Icon
          name="chevron-down"
          size={16}
          className="pointer-events-none absolute top-1/2 right-3 -translate-y-1/2 text-fg-subtle"
        />
      </div>
      {hint ? (
        <p id={hintId} className="text-xs text-fg-subtle">
          {hint}
        </p>
      ) : null}
    </div>
  );
}
