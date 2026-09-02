import { NextRequest, NextResponse } from "next/server";
import { buildEndSessionUrl, oidcEndpoints } from "@/lib/auth/keycloak";
import { SESSION_COOKIE, unsealSession } from "@/lib/auth/session";
import { getAuthSecret, getAuthMode, getKeycloakConfig } from "@/lib/env";

/**
 * GET /api/auth/logout — clears the session cookie, then:
 * - keycloak mode with an id_token: redirects through the realm's
 *   end_session_endpoint (the realm config allows post-logout redirects to
 *   http://localhost:3000/*);
 * - otherwise: straight back to /login.
 */
export async function GET(request: NextRequest) {
  const secure = request.headers.get("x-forwarded-proto") === "https";

  const sealed = request.cookies.get(SESSION_COOKIE)?.value;
  let session = null;
  if (sealed) {
    session = await unsealSession(sealed, getAuthSecret());
  }

  const response = NextResponse.redirect(new URL("/login", request.url));
  response.cookies.set(SESSION_COOKIE, "", {
    httpOnly: true,
    sameSite: "lax",
    secure,
    path: "/",
    maxAge: 0,
  });

  const mode = getAuthMode();
  if (mode === "keycloak" && session?.idToken) {
    const config = getKeycloakConfig();
    const endpoints = oidcEndpoints(config);
    const postLogoutRedirectUri = new URL("/login", request.nextUrl.origin).toString();
    return NextResponse.redirect(
      buildEndSessionUrl({
        endpoints,
        clientId: config.clientId,
        idTokenHint: session.idToken,
        postLogoutRedirectUri,
      }),
    );
  }

  return response;
}
