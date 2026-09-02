import { Badge, type BadgeVariant } from "@/components/ui/Badge";
import type { PaymentState } from "@/lib/api/sdk/payments";
import type { PayoutState } from "@/lib/api/sdk/payouts";
import type { RiskCaseState, RiskCaseSeverity } from "@/lib/api/sdk/risk";
import type { WalletStatus } from "@/lib/api/sdk/wallets";
import type { QuoteState } from "@/lib/api/sdk/fx";

/**
 * Domain state → Badge tone mappings (single place so every table, list and
 * card renders states identically).
 */

export function PaymentStateBadge({ state }: { state: PaymentState }) {
  const variant: BadgeVariant =
    state === "SUCCEEDED"
      ? "success"
      : state === "FAILED" || state === "EXPIRED" || state === "REVERSED" || state === "BLOCKED"
        ? "danger"
        : state === "CANCELLED"
          ? "neutral"
          : "warning";
  return <Badge variant={variant}>{state}</Badge>;
}

export function PayoutStateBadge({ state }: { state: PayoutState }) {
  const variant: BadgeVariant =
    state === "SUCCEEDED"
      ? "success"
      : state === "FAILED" || state === "RETURNED" || state === "BLOCKED"
        ? "danger"
        : state === "CANCELLED"
          ? "neutral"
          : "warning";
  return <Badge variant={variant}>{state}</Badge>;
}

export function WalletStatusBadge({ status }: { status: WalletStatus }) {
  const variant: BadgeVariant =
    status === "active" ? "success" : status === "frozen" ? "warning" : "neutral";
  return <Badge variant={variant} dot>{status}</Badge>;
}

export function QuoteStateBadge({ state }: { state: QuoteState }) {
  const variant: BadgeVariant =
    state === "EXECUTED" ? "success" : state === "EXPIRED" ? "danger" : state === "LOCKED" ? "info" : "warning";
  return <Badge variant={variant}>{state}</Badge>;
}

export function RiskCaseStateBadge({ state }: { state: RiskCaseState }) {
  const variant: BadgeVariant =
    state === "RESOLVED"
      ? "success"
      : state === "DISMISSED"
        ? "neutral"
        : state === "OPEN"
          ? "danger"
          : "warning";
  return <Badge variant={variant}>{state}</Badge>;
}

export function RiskSeverityBadge({ severity }: { severity: RiskCaseSeverity }) {
  const variant: BadgeVariant =
    severity === "critical" ? "danger" : severity === "high" ? "warning" : "info";
  return <Badge variant={variant} mono>{severity}</Badge>;
}

export function DeliveryStateBadge({ state }: { state: "succeeded" | "retrying" | "dead" }) {
  const variant: BadgeVariant =
    state === "succeeded" ? "success" : state === "dead" ? "danger" : "warning";
  return <Badge variant={variant} dot>{state}</Badge>;
}

export function ApiKeyStateBadge({ state }: { state: "active" | "revoked" }) {
  return <Badge variant={state === "active" ? "success" : "neutral"}>{state}</Badge>;
}

export function WebhookEndpointStateBadge({ state }: { state: "active" | "dead" }) {
  return (
    <Badge variant={state === "active" ? "success" : "danger"} dot>
      {state}
    </Badge>
  );
}
