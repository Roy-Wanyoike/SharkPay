import { cookies } from "next/headers";
import { redirect } from "next/navigation";
import { getAuthSecret } from "@/lib/env";
import { SESSION_COOKIE, isSessionExpired, unsealSession, type Session } from "@/lib/auth/session";

/**
 * Server-side session access (App Router: route handlers, layouts, pages).
 * Server-only: imports next/headers, so this file must never be pulled into
 * a client bundle.
 */

export async function getSession(): Promise<Session | null> {
  const jar = await cookies();
  const sealed = jar.get(SESSION_COOKIE)?.value;
  if (!sealed) return null;
  const session = await unsealSession(sealed, getAuthSecret());
  if (!session || isSessionExpired(session)) return null;
  return session;
}

/** Session or redirect to /login — used by the console layout as backstop. */
export async function requireSession(): Promise<Session> {
  const session = await getSession();
  if (!session) {
    redirect("/login");
  }
  return session;
}
