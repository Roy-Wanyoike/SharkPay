"use client";

import { useEffect } from "react";
import { Icon } from "@/components/layout/icons";
import { Button } from "@/components/ui/Button";

/**
 * Route-segment error boundary for the console group: renders a real,
 * actionable error state (retry resets the nearest RSC boundary).
 */
export default function ConsoleError({
  error,
  reset,
}: {
  error: Error & { digest?: string };
  reset: () => void;
}) {
  useEffect(() => {
    console.error("[console] route error:", error);
  }, [error]);

  return (
    <div className="mx-auto flex w-full max-w-lg flex-col items-center gap-4 px-4 py-24 text-center">
      <span className="flex h-12 w-12 items-center justify-center rounded-full bg-danger-soft text-danger">
        <Icon name="alert" size={24} />
      </span>
      <div className="space-y-1">
        <h2 className="text-base font-semibold text-fg">This view failed to load</h2>
        <p className="text-sm text-fg-muted">
          The console could not render this page. Retrying is safe — all
          mutations are idempotency-keyed.
        </p>
        {error.digest ? (
          <p className="font-mono text-xs text-fg-subtle">digest: {error.digest}</p>
        ) : null}
      </div>
      <Button variant="secondary" onClick={reset} icon="spinner">
        Retry
      </Button>
    </div>
  );
}
