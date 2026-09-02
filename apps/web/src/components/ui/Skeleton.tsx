import { cn } from "@/lib/utils";

export interface SkeletonProps {
  className?: string;
}

/** Single pulse block. */
export function Skeleton({ className }: SkeletonProps) {
  return (
    <div
      aria-hidden="true"
      className={cn("animate-pulse rounded-lg bg-surface-2", className)}
    />
  );
}

export interface SkeletonTextProps {
  lines?: number;
  className?: string;
}

/** Multi-line text placeholder. */
export function SkeletonText({ lines = 3, className }: SkeletonTextProps) {
  return (
    <div
      role="status"
      aria-label="Loading"
      className={cn("space-y-2", className)}
    >
      {Array.from({ length: lines }, (_, index) => (
        <Skeleton
          key={index}
          className={cn("h-4", index === lines - 1 ? "w-2/5" : "w-full")}
        />
      ))}
    </div>
  );
}

export interface TableSkeletonProps {
  rows?: number;
  columns?: number;
}

/** Table-shaped placeholder for Suspense fallbacks. */
export function TableSkeleton({ rows = 6, columns = 6 }: TableSkeletonProps) {
  return (
    <div role="status" aria-label="Loading table" className="w-full">
      <div className="flex gap-4 border-b border-border-subtle px-4 py-3">
        {Array.from({ length: columns }, (_, index) => (
          <Skeleton key={index} className="h-3 flex-1" />
        ))}
      </div>
      {Array.from({ length: rows }, (_, rowIndex) => (
        <div
          key={rowIndex}
          className="flex gap-4 border-b border-border-subtle/50 px-4 py-3.5"
        >
          {Array.from({ length: columns }, (_, columnIndex) => (
            <Skeleton
              key={columnIndex}
              className={cn("h-4", columnIndex === 0 ? "w-40 shrink-0" : "h-4 flex-1")}
            />
          ))}
        </div>
      ))}
    </div>
  );
}

export interface StatTileSkeletonProps {
  count?: number;
}

export function StatTileSkeleton({ count = 4 }: StatTileSkeletonProps) {
  return (
    <div
      role="status"
      aria-label="Loading metrics"
      className="grid gap-4 sm:grid-cols-2 xl:grid-cols-4"
    >
      {Array.from({ length: count }, (_, index) => (
        <div
          key={index}
          className="space-y-3 rounded-card border border-border-subtle bg-surface p-5"
        >
          <Skeleton className="h-3 w-24" />
          <Skeleton className="h-8 w-32" />
          <Skeleton className="h-3 w-20" />
        </div>
      ))}
    </div>
  );
}
