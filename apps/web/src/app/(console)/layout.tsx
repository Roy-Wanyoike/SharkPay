import { redirect } from "next/navigation";
import type { ReactNode } from "react";
import { AppShell } from "@/components/layout/AppShell";
import { getSession } from "@/lib/auth/server";
import { getAuthMode, getEnvironmentBadge } from "@/lib/env";

/**
 * Console shell layout: middleware is the primary gate; this server-side
 * check is the defense-in-depth backstop (also covers direct renders).
 */
export default async function ConsoleLayout({ children }: { children: ReactNode }) {
  const session = await getSession();
  if (!session) {
    redirect("/login");
  }

  return (
    <AppShell
      user={{
        name: session.user.name,
        email: session.user.email ?? null,
        roles: session.user.roles,
      }}
      environment={getEnvironmentBadge()}
      authMode={getAuthMode()}
    >
      {children}
    </AppShell>
  );
}
