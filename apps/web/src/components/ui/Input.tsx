"use client";

import { useId, type InputHTMLAttributes } from "react";
import { cn } from "@/lib/utils";

export interface InputProps extends Omit<InputHTMLAttributes<HTMLInputElement>, "id"> {
  /** Visible label (always rendered — never placeholder-as-label). */
  label: string;
  /** Helper text below the field. */
  hint?: string;
  /** Error text — switches the ring to danger and wires aria-describedby. */
  error?: string;
}

export function Input({ label, hint, error, className, required, ...rest }: InputProps) {
  const id = useId();
  const hintId = hint ? `${id}-hint` : undefined;
  const errorId = error ? `${id}-error` : undefined;
  const describedBy = [errorId, hintId].filter(Boolean).join(" ") || undefined;
  return (
    <div className="space-y-1.5">
      <label htmlFor={id} className="block text-xs font-medium text-fg-muted">
        {label}
        {required ? (
          <span aria-hidden="true" className="ml-0.5 text-danger">
            *
          </span>
        ) : null}
      </label>
      <input
        id={id}
        required={required}
        aria-invalid={error ? true : undefined}
        aria-describedby={describedBy}
        className={cn(
          "h-10 w-full rounded-lg border bg-surface-2 px-3 text-sm text-fg placeholder:text-fg-subtle",
          "focus:outline-none focus-visible:outline-2 focus-visible:outline-offset-1 focus-visible:outline-accent",
          error ? "border-danger" : "border-border-subtle",
          className,
        )}
        {...rest}
      />
      {hint && !error ? (
        <p id={hintId} className="text-xs text-fg-subtle">
          {hint}
        </p>
      ) : null}
      {error ? (
        <p id={errorId} role="alert" className="text-xs text-danger">
          {error}
        </p>
      ) : null}
    </div>
  );
}
