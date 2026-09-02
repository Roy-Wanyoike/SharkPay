import type { HTMLAttributes, ReactNode } from "react";
import { cn } from "@/lib/utils";

export interface CardProps extends HTMLAttributes<HTMLDivElement> {}

export function Card({ className, ...rest }: CardProps) {
  return (
    <div
      className={cn(
        "rounded-card border border-border-subtle bg-surface shadow-[0_1px_2px_rgb(0_0_0/0.25)]",
        className,
      )}
      {...rest}
    />
  );
}

export interface CardHeaderProps extends Omit<HTMLAttributes<HTMLDivElement>, "title"> {
  title: ReactNode;
  description?: ReactNode;
  /** Right-aligned actions row. */
  actions?: ReactNode;
}

export function CardHeader({ title, description, actions, className, ...rest }: CardHeaderProps) {
  return (
    <div
      className={cn(
        "flex flex-wrap items-start justify-between gap-3 border-b border-border-subtle px-5 py-4",
        className,
      )}
      {...rest}
    >
      <div className="min-w-0 space-y-1">
        <h3 className="text-sm font-semibold text-fg">{title}</h3>
        {description ? (
          <p className="text-xs text-fg-muted">{description}</p>
        ) : null}
      </div>
      {actions ? <div className="flex shrink-0 items-center gap-2">{actions}</div> : null}
    </div>
  );
}

export function CardContent({ className, ...rest }: HTMLAttributes<HTMLDivElement>) {
  return <div className={cn("px-5 py-4", className)} {...rest} />;
}

export function CardFooter({ className, ...rest }: HTMLAttributes<HTMLDivElement>) {
  return (
    <div
      className={cn("border-t border-border-subtle px-5 py-3", className)}
      {...rest}
    />
  );
}
