import { NextRequest, NextResponse } from "next/server";
import { buildMockSession } from "@/lib/auth/mock";
import { createPkcePair, randomState } from "@/lib/auth/pkce";
import {
  AUTH_FLOW_COOKIE,
  AUTH_FLOW_TTL_SECONDS,
  SESSION_COOKIE,
  SESSION_TTL_SECONDS,
  sealSession,
} from "@/lib/auth/session";
import { buildAuthorizeUrl, oidcEndpoints } from "@/lib/auth/keycloak";
import { getAuthMode, getAuthSecret, getKeycloakConfig } from "@/lib/env";

/** Only allow internal, single-slash-relative redirect targets. */
function sanitizeNextPath(value: string | null): string | null {
  if (!value || !value.startsWith("/") || value.startsWith("//")) {
    return null;
  }
  return value;
}

function isSecureRequest(request: NextRequest): boolean {
  return request.headers.get("x-forwarded-proto") === "https";
}

const baseCookie = {
  httpOnly: true,
  sameSite: "lax",
  path: "/",
} as const;

/**
 * GET /api/auth/login?next=/payments
 *
 * - mock mode: seals a mock session cookie and redirects straight in.
 * - keycloak mode: generates PKCE S256 + state, parks them in a 10-minute
 *   HttpOnly cookie, and redirects to the realm's authorize endpoint
 *   (client sharkpay-web, standard flow + PKCE per the imported realm).
 */
export async function GET(request: NextRequest) {
  const nextPath = sanitizeNextPath(request.nextUrl.searchParams.get("next"));
  const secure = isSecureRequest(request);
  const mode = getAuthMode();

  if (mode === "mock") {
    const session = buildMockSession();
    const response = NextResponse.redirect(new URL(nextPath ?? "/", request.url));
    response.cookies.set(SESSION_COOKIE, await sealSession(session, getAuthSecret()), {
      ...baseCookie,
      secure,
      maxAge: SESSION_TTL_SECONDS,
    });
    return response;
  }

  const config = getKeycloakConfig();
  const endpoints = oidcEndpoints(config);
  const { verifier, challenge } = await createPkcePair();
  const state = randomState();
  const redirectUri = new URL("/api/auth/callback", request.nextUrl.origin).toString();

  const authorizeUrl = buildAuthorizeUrl({
    endpoints,
    clientId: config.clientId,
    redirectUri,
    state,
    codeChallenge: challenge,
  });

  const response = NextResponse.redirect(authorizeUrl);
  response.cookies.set(
    AUTH_FLOW_COOKIE,
    JSON.stringify({ verifier, state, next: nextPath }),
    {
      ...baseCookie,
      secure,
      maxAge: AUTH_FLOW_TTL_SECONDS,
    },
  );
  return response;
}
