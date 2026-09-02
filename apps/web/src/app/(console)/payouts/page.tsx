import { Suspense } from "react";
import type { Metadata } from "next";
import { PageShell } from "@/components/shared/PageShell";
import { TableSkeleton } from "@/components/ui/Skeleton";
import { PayoutsView } from "@/components/views/PayoutsView";
import { ApiClient } from "@/lib/api/client";
import { apiClientForSession } from "@/lib/api/server";
import {
  listPayouts,
  type PayoutRail,
  type PayoutState,
} from "@/lib/api/sdk/payouts";

const PAYOUT_STATES: readonly string[] = [
  "CREATED",
  "PENDING_RISK",
  "PROCESSING",
  "SENT",
  "SUCCEEDED",
  "FAILED",
  "RETURNED",
  "BLOCKED",
  "CANCELLED",
];
const PAYOUT_RAILS: readonly string[] = ["mpesa", "bank", "on_chain"];

function asPayoutState(value: string | undefined): PayoutState | undefined {
  return value && PAYOUT_STATES.includes(value) ? (value as PayoutState) : undefined;
}

function asPayoutRail(value: string | undefined): PayoutRail | undefined {
  return value && PAYOUT_RAILS.includes(value) ? (value as PayoutRail) : undefined;
}
import { getSession } from "@/lib/auth/server";
import { loadWithFallback } from "@/lib/data/load";
import { seedPayoutsPage } from "@/lib/seed/seed";

export const metadata: Metadata = {
  title: "Payouts",
};

interface PageSearchParams {
  state?: string;
  rail?: string;
}

async function PayoutsData({ state, rail }: PageSearchParams) {
  const session = await getSession();
  const client: ApiClient = session
    ? apiClientForSession(session)
    : new ApiClient({ accessToken: null });

  const result = await loadWithFallback(
    "payouts:list",
    () => listPayouts(client, { state: asPayoutState(state), rail: asPayoutRail(rail) }),
    () => seedPayoutsPage({ state, rail }),
  );

  return <PayoutsView page={result.data} source={result.source} filters={{ state, rail }} />;
}

export default async function PayoutsPage({
  searchParams,
}: {
  searchParams?: Promise<PageSearchParams>;
}) {
  const params = (await searchParams) ?? {};
  return (
    <PageShell
      title="Payouts"
      description="External withdrawals from SharkPay wallets to M-Pesa, bank and on-chain destinations, with return compensation flows."
    >
      <Suspense fallback={<TableSkeleton rows={8} columns={8} />}>
        <PayoutsData state={params.state} rail={params.rail} />
      </Suspense>
    </PageShell>
  );
}
