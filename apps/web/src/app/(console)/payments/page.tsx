import { Suspense } from "react";
import type { Metadata } from "next";
import { PageShell } from "@/components/shared/PageShell";
import { PaymentsView } from "@/components/views/PaymentsView";
import { TableSkeleton } from "@/components/ui/Skeleton";
import { ApiClient } from "@/lib/api/client";
import { apiClientForSession } from "@/lib/api/server";
import {
  listPayments,
  type PaymentListFilters,
  type PaymentState,
  type Rail,
} from "@/lib/api/sdk/payments";

const PAYMENT_STATES: readonly string[] = [
  "CREATED",
  "PENDING_PROVIDER",
  "PROCESSING",
  "SUCCEEDED",
  "FAILED",
  "EXPIRED",
  "REVERSED",
  "BLOCKED",
  "CANCELLED",
];
const RAILS: readonly string[] = ["honeycoin", "mpesa", "bank", "on_chain"];

function asPaymentState(value: string | undefined): PaymentState | undefined {
  return value && PAYMENT_STATES.includes(value) ? (value as PaymentState) : undefined;
}

function asRail(value: string | undefined): Rail | undefined {
  return value && RAILS.includes(value) ? (value as Rail) : undefined;
}
import { getSession } from "@/lib/auth/server";
import { loadWithFallback } from "@/lib/data/load";
import { seedPaymentsPage } from "@/lib/seed/seed";

export const metadata: Metadata = {
  title: "Payments",
};

interface PageSearchParams {
  state?: string;
  rail?: string;
  cursor?: string;
}

async function PaymentsData({ state, rail, cursor }: PageSearchParams) {
  const session = await getSession();
  const client: ApiClient = session
    ? apiClientForSession(session)
    : new ApiClient({ accessToken: null });

  const filters: PaymentListFilters = {
    state: asPaymentState(state),
    rail: asRail(rail),
    cursor,
    limit: 25,
  };

  const result = await loadWithFallback(
    "payments:list",
    () => listPayments(client, filters),
    () => seedPaymentsPage({ state, rail }),
  );

  return (
    <PaymentsView
      page={result.data}
      source={result.source}
      filters={{ state, rail }}
      nextCursor={result.data.next_cursor ?? null}
    />
  );
}

export default async function PaymentsPage({
  searchParams,
}: {
  searchParams?: Promise<PageSearchParams>;
}) {
  const params = (await searchParams) ?? {};
  return (
    <PageShell
      title="Payments"
      description="Inbound payment intents — collect money into SharkPay wallets across the honeycoin, M-Pesa, bank and on-chain rails."
    >
      <Suspense fallback={<TableSkeleton rows={8} columns={7} />}>
        <PaymentsData state={params.state} rail={params.rail} cursor={params.cursor} />
      </Suspense>
    </PageShell>
  );
}
