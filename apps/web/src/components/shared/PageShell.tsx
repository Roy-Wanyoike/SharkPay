import type { ReactNode } from "react";

export interface PageShellProps {
  title: string;
  description?: string;
  /** Header actions (buttons, badges). */
  actions?: ReactNode;
  children: ReactNode;
}

/** Consistent page header + content column for every console page. */
export function PageShell({ title, description, actions, children }: PageShellProps) {
  return (
    <div className="mx-auto w-full max-w-7xl px-4 py-6 sm:px-6 lg:px-8">
      <header className="mb-6 flex flex-wrap items-start justify-between gap-3">
        <div>
          <h1 className="text-xl font-semibold text-fg">{title}</h1>
          {description ? (
            <p className="mt-1 max-w-2xl text-sm text-fg-muted">{description}</p>
          ) : null}
        </div>
        {actions ? <div className="flex items-center gap-2">{actions}</div> : null}
      </header>
      {children}
    </div>
  );
}
