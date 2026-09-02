import { Badge } from "@/components/ui/Badge";
import { Card, CardContent, CardHeader } from "@/components/ui/Card";
import { ThemeToggle } from "@/components/layout/Topbar";
import { formatDateTimeFull } from "@/lib/format";

export interface SettingsViewProps {
  user: {
    name: string;
    preferredUsername: string;
    email: string | null;
    roles: readonly string[];
  };
  session: {
    mode: "keycloak" | "mock";
    issuedAt: number;
    expiresAt: number;
  };
  environment: {
    apiBaseUrl: string;
    authMode: "keycloak" | "mock";
    keycloak: { url: string; realm: string; clientId: string };
    badge: "sandbox" | "prod";
  };
}

function Row({ label, value }: { label: string; value: string }) {
  return (
    <div className="flex items-center justify-between gap-4 border-b border-border-subtle py-2.5 last:border-b-0">
      <dt className="text-xs text-fg-muted">{label}</dt>
      <dd className="truncate font-mono text-xs text-fg">{value}</dd>
    </div>
  );
}

export function SettingsView({ user, session, environment }: SettingsViewProps) {
  return (
    <div className="grid gap-6 lg:grid-cols-2">
      <Card>
        <CardHeader title="Profile" description="Identity from the current session." />
        <CardContent>
          <dl>
            <Row label="Display name" value={user.name} />
            <Row label="Username" value={user.preferredUsername} />
            <Row label="Email" value={user.email ?? "—"} />
          </dl>
          <div className="mt-4">
            <p className="mb-2 text-xs text-fg-muted">Roles</p>
            <div className="flex flex-wrap gap-1.5">
              {user.roles.length > 0 ? (
                user.roles.map((role) => (
                  <Badge key={role} variant="outline" mono>
                    {role}
                  </Badge>
                ))
              ) : (
                <Badge variant="neutral">no roles</Badge>
              )}
            </div>
          </div>
        </CardContent>
      </Card>

      <Card>
        <CardHeader
          title="Session"
          description="AES-256-GCM sealed HttpOnly cookie."
          actions={
            <a
              href="/api/auth/logout"
              className="inline-flex h-9 items-center gap-2 rounded-lg bg-danger px-3 text-xs font-medium text-white hover:opacity-90 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-danger"
            >
              Sign out
            </a>
          }
        />
        <CardContent>
          <dl>
            <Row label="Auth mode" value={session.mode} />
            <Row label="Issued" value={formatDateTimeFull(new Date(session.issuedAt * 1000).toISOString())} />
            <Row label="Expires" value={formatDateTimeFull(new Date(session.expiresAt * 1000).toISOString())} />
            <Row label="Cookie" value="sharkpay_session (HttpOnly, SameSite=Lax)" />
          </dl>
        </CardContent>
      </Card>

      <Card>
        <CardHeader title="Environment" description="Resolved from console env vars." />
        <CardContent>
          <dl>
            <Row label="Environment badge" value={environment.badge} />
            <Row label="Auth mode" value={environment.authMode} />
            <Row label="API base URL" value={environment.apiBaseUrl} />
            <Row label="Keycloak URL" value={environment.keycloak.url} />
            <Row label="Keycloak realm" value={environment.keycloak.realm} />
            <Row label="OIDC client" value={environment.keycloak.clientId} />
          </dl>
        </CardContent>
      </Card>

      <Card>
        <CardHeader title="Appearance" description="Dark-first console palette with a light option." />
        <CardContent className="flex items-center justify-between gap-4">
          <div>
            <p className="text-sm font-medium text-fg">Theme</p>
            <p className="mt-1 text-xs text-fg-muted">
              Dark is the default. The choice is persisted per browser and applied before
              first paint.
            </p>
          </div>
          <ThemeToggle />
        </CardContent>
      </Card>
    </div>
  );
}
