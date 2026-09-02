import { Suspense } from "react";
import type { Metadata } from "next";
import { PageShell } from "@/components/shared/PageShell";
import { TableSkeleton } from "@/components/ui/Skeleton";
import { WalletsView } from "@/components/views/WalletsView";
import { ApiClient } from "@/lib/api/client";
import { apiClientForSession } from "@/lib/api/server";
import { listWallets, type WalletStatus } from "@/lib/api/sdk/wallets";
import type { Currency } from "@/lib/api/sdk/types";

const CURRENCIES: readonly string[] = ["KES", "USD", "EUR", "GBP", "USDC", "USDT"];
const WALLET_STATUSES: readonly string[] = ["active", "frozen", "closed"];

function asCurrency(value: string | undefined): Currency | undefined {
  return value && CURRENCIES.includes(value) ? (value as Currency) : undefined;
}

function asWalletStatus(value: string | undefined): WalletStatus | undefined {
  return value && WALLET_STATUSES.includes(value) ? (value as WalletStatus) : undefined;
}
import { getSession } from "@/lib/auth/server";
import { loadWithFallback } from "@/lib/data/load";
import { seedWalletsPage } from "@/lib/seed/seed";

export const metadata: Metadata = {
  title: "Wallets",
};

interface PageSearchParams {
  currency?: string;
  status?: string;
}

async function WalletsData({ currency, status }: PageSearchParams) {
  const session = await getSession();
  const client: ApiClient = session
    ? apiClientForSession(session)
    : new ApiClient({ accessToken: null });

  const result = await loadWithFallback(
    "wallets:list",
    () => listWallets(client, { currency: asCurrency(currency), status: asWalletStatus(status) }),
    () => seedWalletsPage({ currency, status }),
  );

  return <WalletsView page={result.data} source={result.source} filters={{ currency, status }} />;
}

export default async function WalletsPage({
  searchParams,
}: {
  searchParams?: Promise<PageSearchParams>;
}) {
  const params = (await searchParams) ?? {};
  return (
    <PageShell
      title="Wallets"
      description="Multi-currency balance containers with their available/pending/held partitions — projections of the immutable ledger."
    >
      <Suspense fallback={<TableSkeleton rows={6} columns={7} />}>
        <WalletsData currency={params.currency} status={params.status} />
      </Suspense>
    </PageShell>
  );
}
