import type { Metadata } from "next";
import { Icon, SharkpayMark } from "@/components/layout/icons";
import { Badge } from "@/components/ui/Badge";
import { getAuthMode, getEnvironmentBadge } from "@/lib/env";

export const metadata: Metadata = {
  title: "Sign in",
};

const ERROR_MESSAGES: Record<string, string> = {
  missing_code: "The sign-in response was incomplete. Please try again.",
  missing_flow: "Your sign-in attempt expired. Please try again.",
  invalid_flow: "Your sign-in attempt could not be verified. Please try again.",
  state_mismatch: "Sign-in state did not match — possible session tampering. Please try again.",
  token_exchange_failed: "Keycloak rejected the sign-in code. Please try again.",
  invalid_id_token: "Keycloak returned an invalid identity token. Please try again.",
};

function describeError(error: string | string[] | undefined): string | null {
  if (!error || Array.isArray(error)) return null;
  if (ERROR_MESSAGES[error]) return ERROR_MESSAGES[error];
  if (error.startsWith("keycloak:")) {
    return `Keycloak returned an error (${error.slice("keycloak:".length)}).`;
  }
  return null;
}

export default async function LoginPage({
  searchParams,
}: {
  searchParams?: Promise<Record<string, string | string[] | undefined>>;
}) {
  const params = (await searchParams) ?? {};
  const mode = getAuthMode();
  const environment = getEnvironmentBadge();
  const errorMessage = describeError(params.error);
  const nextPath =
    typeof params.next === "string" && params.next.startsWith("/") && !params.next.startsWith("//")
      ? params.next
      : undefined;

  return (
    <main className="flex min-h-dvh items-center justify-center bg-background px-4">
      <div className="w-full max-w-sm">
        <div className="mb-6 flex flex-col items-center gap-3 text-center">
          <span className="text-accent">
            <SharkpayMark size={44} />
          </span>
          <div>
            <h1 className="text-lg font-semibold text-fg">SharkPay Console</h1>
            <p className="mt-1 text-sm text-fg-muted">
              Operations &amp; developer console for the SharkPay platform.
            </p>
          </div>
          <div className="flex items-center gap-2">
            <Badge variant={environment === "prod" ? "danger" : "accent"}>{environment}</Badge>
            <Badge variant="outline">{mode === "mock" ? "mock auth" : "Keycloak OIDC"}</Badge>
          </div>
        </div>

        <div className="rounded-card border border-border-subtle bg-surface p-6 shadow-[0_8px_30px_rgb(0_0_0/0.25)]">
          {errorMessage ? (
            <div
              role="alert"
              className="mb-4 flex items-start gap-2.5 rounded-lg border border-danger/40 bg-danger-soft px-3 py-2.5 text-xs text-fg"
            >
              <span className="mt-0.5 text-danger">
                <Icon name="alert" size={16} />
              </span>
              <p>{errorMessage}</p>
            </div>
          ) : null}

          <form action="/api/auth/login" method="get" className="space-y-4">
            {nextPath ? <input type="hidden" name="next" value={nextPath} /> : null}
            <button
              type="submit"
              className="inline-flex h-11 w-full items-center justify-center gap-2 rounded-lg bg-accent px-4 text-sm font-medium text-accent-fg transition-colors hover:bg-accent-hover focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
            >
              <Icon name="user" size={18} />
              {mode === "mock" ? "Sign in (dev mock session)" : "Sign in with SharkPay SSO"}
            </button>
          </form>

          <p className="mt-4 text-center text-xs text-fg-subtle">
            {mode === "mock"
              ? "AUTH_MODE=mock — a synthetic operator session is created locally; no Keycloak required."
              : "Redirects to the sharkpay realm (authorization code + PKCE). Access is limited to console operators."}
          </p>
        </div>

        <p className="mt-6 text-center text-xs text-fg-subtle">
          Session cookies are HttpOnly and AES-256-GCM sealed. Never share console access.
        </p>
      </div>
    </main>
  );
}
