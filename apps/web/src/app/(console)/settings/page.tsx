import type { Metadata } from "next";
import { PageShell } from "@/components/shared/PageShell";
import { SettingsView } from "@/components/views/SettingsView";
import { requireSession } from "@/lib/auth/server";
import { getApiBaseUrl, getAuthMode, getEnvironmentBadge, getKeycloakConfig } from "@/lib/env";

export const metadata: Metadata = {
  title: "Settings",
};

export default async function SettingsPage() {
  const session = await requireSession();
  const keycloak = getKeycloakConfig();

  return (
    <PageShell
      title="Settings"
      description="Operator profile, session details and the console's resolved environment."
    >
      <SettingsView
        user={{
          name: session.user.name,
          preferredUsername: session.user.preferred_username,
          email: session.user.email ?? null,
          roles: session.user.roles,
        }}
        session={{
          mode: session.mode,
          issuedAt: session.issuedAt,
          expiresAt: session.expiresAt,
        }}
        environment={{
          apiBaseUrl: getApiBaseUrl(),
          authMode: getAuthMode(),
          keycloak,
          badge: getEnvironmentBadge(),
        }}
      />
    </PageShell>
  );
}
