import Link from "next/link";
import { EmptyState } from "@/components/ui/EmptyState";

export default function NotFound() {
  return (
    <main className="flex min-h-dvh items-center justify-center bg-background px-4">
      <div className="w-full max-w-md rounded-card border border-border-subtle bg-surface">
        <EmptyState
          icon="alert"
          title="Page not found"
          description="The console page you were looking for does not exist (or was renamed during the foundation build)."
          action={
            <Link
              href="/"
              className="inline-flex h-10 items-center rounded-lg bg-accent px-4 text-sm font-medium text-accent-fg hover:bg-accent-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
            >
              Back to dashboard
            </Link>
          }
        />
      </div>
    </main>
  );
}
