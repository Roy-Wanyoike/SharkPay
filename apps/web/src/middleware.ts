import type { NextRequest } from "next/server";
import { NextResponse } from "next/server";
import { SESSION_COOKIE, isSessionExpired, unsealSession } from "@/lib/auth/session";
import { getAuthSecret } from "@/lib/env";

/**
 * Route protection: every app route (see config.matcher) requires a valid,
 * unexpired, sealed session cookie. Edge-compatible — unsealSession uses
 * Web Crypto only.
 *
 * Note: AUTH_SECRET must be present in the runtime environment (dev loads
 * .env* automatically; production bakes it at build time for the edge
 * bundle).
 */

const LOGIN_PATH = "/login";

export async function middleware(request: NextRequest) {
  const sealed = request.cookies.get(SESSION_COOKIE)?.value;
  let authenticated = false;

  if (sealed) {
    try {
      const session = await unsealSession(sealed, getAuthSecret());
      authenticated = session !== null && !isSessionExpired(session);
    } catch {
      authenticated = false;
    }
  }

  if (!authenticated) {
    const loginUrl = new URL(LOGIN_PATH, request.url);
    const target = `${request.nextUrl.pathname}${request.nextUrl.search}`;
    if (target && target !== "/") {
      loginUrl.searchParams.set("next", target);
    }
    return NextResponse.redirect(loginUrl);
  }

  return NextResponse.next();
}

export const config = {
  matcher: [
    // All paths except: /login, the auth API routes, Next internals, and
    // any static file (paths containing a dot).
    "/((?!login$|api/auth/|_next/|favicon\\.ico|robots\\.txt|.*\\..*).*)",
  ],
};
