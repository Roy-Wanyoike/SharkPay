import { NextRequest, NextResponse } from "next/server";
import {
  exchangeAuthorizationCode,
  extractRoles,
  oidcEndpoints,
  parseIdTokenClaims,
} from "@/lib/auth/keycloak";
import {
  AUTH_FLOW_COOKIE,
  SESSION_COOKIE,
  SESSION_TTL_SECONDS,
  sealSession,
  type Session,
} from "@/lib/auth/session";
import { getAuthSecret, getKeycloakConfig } from "@/lib/env";

interface AuthFlow {
  verifier: string;
  state: string;
  next: string | null;
}

function redirectToLogin(request: NextRequest, reason: string): NextResponse {
  return NextResponse.redirect(
    new URL(`/login?error=${encodeURIComponent(reason)}`, request.url),
  );
}

/**
 * GET /api/auth/callback — Keycloak authorization-code redirect target.
 *
 * Validates the state against the HttpOnly flow cookie, exchanges the code
 * with the PKCE verifier server-side, then seals the session (access token,
 * refresh token, id token, claims) into the encrypted cookie.
 */
export async function GET(request: NextRequest) {
  const secure = request.headers.get("x-forwarded-proto") === "https";
  const params = request.nextUrl.searchParams;

  const oauthError = params.get("error");
  if (oauthError) {
    return redirectToLogin(request, `keycloak:${oauthError}`);
  }

  const code = params.get("code");
  const state = params.get("state");
  const flowCookie = request.cookies.get(AUTH_FLOW_COOKIE)?.value;

  if (!code || !state) {
    return redirectToLogin(request, "missing_code");
  }
  if (!flowCookie) {
    return redirectToLogin(request, "missing_flow");
  }

  let flow: AuthFlow;
  try {
    flow = JSON.parse(flowCookie) as AuthFlow;
    if (typeof flow.verifier !== "string" || typeof flow.state !== "string") {
      throw new Error("bad flow shape");
    }
  } catch {
    return redirectToLogin(request, "invalid_flow");
  }

  if (flow.state !== state) {
    // Possible CSRF/login-CSRF — fail closed.
    return redirectToLogin(request, "state_mismatch");
  }

  const config = getKeycloakConfig();
  const endpoints = oidcEndpoints(config);
  const redirectUri = new URL("/api/auth/callback", request.nextUrl.origin).toString();

  let tokens;
  try {
    tokens = await exchangeAuthorizationCode({
      tokenEndpoint: endpoints.token,
      clientId: config.clientId,
      code,
      redirectUri,
      codeVerifier: flow.verifier,
    });
  } catch (error) {
    console.error("[auth] token exchange failed:", error);
    return redirectToLogin(request, "token_exchange_failed");
  }

  const claims = tokens.id_token ? parseIdTokenClaims(tokens.id_token) : null;
  if (!claims?.sub) {
    return redirectToLogin(request, "invalid_id_token");
  }

  const nowSeconds = Math.floor(Date.now() / 1000);
  const session: Session = {
    user: {
      sub: claims.sub,
      name: claims.name ?? claims.preferred_username ?? "Operator",
      preferred_username: claims.preferred_username ?? claims.sub,
      email: claims.email,
      roles: extractRoles(claims),
    },
    accessToken: tokens.access_token,
    refreshToken: tokens.refresh_token,
    idToken: tokens.id_token,
    issuedAt: nowSeconds,
    expiresAt: nowSeconds + tokens.expires_in,
    mode: "keycloak",
  };

  const response = NextResponse.redirect(new URL(flow.next ?? "/", request.url));
  response.cookies.set(SESSION_COOKIE, await sealSession(session, getAuthSecret()), {
    httpOnly: true,
    sameSite: "lax",
    secure,
    path: "/",
    maxAge: SESSION_TTL_SECONDS,
  });
  response.cookies.delete(AUTH_FLOW_COOKIE);
  return response;
}
