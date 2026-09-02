import { Suspense } from "react";
import type { Metadata } from "next";
import { PageShell } from "@/components/shared/PageShell";
import { StatTileSkeleton, TableSkeleton } from "@/components/ui/Skeleton";
import { FxView } from "@/components/views/FxView";
import { seedConversions, seedQuotes } from "@/lib/seed/seed";

export const metadata: Metadata = {
  title: "FX",
};

/**
 * FX reads are provisional: the merged fx.yaml defines only POST /fx/quotes
 * and POST /fx/convert — there are no list endpoints yet (contracts are
 * append-only per ADR 003 §2). Until they land, this view renders the
 * clearly-marked seed read model; the typed createQuote/convert SDK calls
 * are wired and ready for the moment writes go live.
 */
async function FxData() {
  return (
    <FxView quotes={seedQuotes} conversions={seedConversions} source="seed" markupBps={150} />
  );
}

export default function FxPage() {
  return (
    <PageShell
      title="FX"
      description="TTL'd quotes and wallet-to-wallet conversions — integer-exact rates, 4-leg ledger postings."
    >
      <Suspense
        fallback={
          <div className="space-y-6">
            <StatTileSkeleton count={3} />
            <TableSkeleton rows={4} columns={7} />
            <TableSkeleton rows={4} columns={6} />
          </div>
        }
      >
        <FxData />
      </Suspense>
    </PageShell>
  );
}
