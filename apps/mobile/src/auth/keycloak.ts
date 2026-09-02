/**
 * Pure Keycloak/OIDC helpers — mirrored from apps/web/src/lib/auth/keycloak.ts
 * so both clients derive identical endpoints/claims semantics.
 *
 * The token-endpoint calls themselves go through expo-auth-session
 * (src/auth/gateway.ts); everything here is deterministic and unit-testable.
 */

import type { IdTokenClaims, KeycloakClientConfig, Session } from './types';
import { decodeBase64UrlToString } from './encoding';

/** The OIDC endpoints a Keycloak realm exposes for a client. */
export interface OidcEndpoints {
  authorize: string;
  token: string;
  logout: string;
  /** RFC 7009 token revocation. */
  revocation: string;
}

/** `{url}/realms/{realm}/protocol/openid-connect/...` */
export function oidcEndpoints(config: Pick<KeycloakClientConfig, 'url' | 'realm'>): OidcEndpoints {
  const base = `${config.url.replace(/\/+$/, '')}/realms/${encodeURIComponent(
    config.realm,
  )}/protocol/openid-connect`;
  return {
    authorize: `${base}/auth`,
    token: `${base}/token`,
    logout: `${base}/logout`,
    revocation: `${base}/revoke`,
  };
}

/** The discovery document expo-auth-session needs, derived from the issuer. */
export function discoveryDocument(config: Pick<KeycloakClientConfig, 'url' | 'realm'>): {
  authorizationEndpoint: string;
  tokenEndpoint: string;
  revocationEndpoint: string;
} {
  const endpoints = oidcEndpoints(config);
  return {
    authorizationEndpoint: endpoints.authorize,
    tokenEndpoint: endpoints.token,
    revocationEndpoint: endpoints.revocation,
  };
}

/** The OIDC issuer identifier (`{url}/realms/{realm}`). */
export function issuerUri(config: Pick<KeycloakClientConfig, 'url' | 'realm'>): string {
  return `${config.url.replace(/\/+$/, '')}/realms/${encodeURIComponent(config.realm)}`;
}

/**
 * Decodes (does NOT verify the signature of) the id_token payload. The token
 * arrives directly from Keycloak's token endpoint over TLS inside the app
 * process, so trusting the server response — not verifying the JWT locally —
 * is the documented stance for this foundation (same as the web console).
 * Returns `null` for anything malformed.
 */
export function parseIdTokenClaims(idToken: string): IdTokenClaims | null {
  const parts = idToken.split('.');
  if (parts.length !== 3) {
    return null;
  }
  try {
    const json = decodeBase64UrlToString(parts[1] ?? '');
    const claims: unknown = JSON.parse(json) as unknown;
    if (typeof claims !== 'object' || claims === null) {
      return null;
    }
    const record = claims as Record<string, unknown>;
    const sub = record['sub'];
    if (typeof sub !== 'string') {
      return null;
    }
    const realmAccess = record['realm_access'];
    const roles = Array.isArray((realmAccess as Record<string, unknown> | undefined)?.['roles'])
      ? ((realmAccess as Record<string, unknown>)['roles'] as unknown[]).filter(
          (role): role is string => typeof role === 'string',
        )
      : undefined;
    return {
      sub,
      ...(typeof record['name'] === 'string' ? { name: record['name'] } : {}),
      ...(typeof record['preferred_username'] === 'string'
        ? { preferred_username: record['preferred_username'] }
        : {}),
      ...(typeof record['email'] === 'string' ? { email: record['email'] } : {}),
      ...(typeof record['exp'] === 'number' ? { exp: record['exp'] } : {}),
      ...(roles !== undefined ? { realmAccess: { roles } } : {}),
    };
  } catch {
    return null;
  }
}

/**
 * The subset of expo-auth-session's `TokenResponse` the app consumes
 * (structural, so tests can construct plain objects).
 */
export interface TokenResponseLike {
  accessToken: string;
  refreshToken?: string;
  idToken?: string;
  /** Token issue time, epoch SECONDS. */
  issuedAt: number;
  /** Access-token lifetime, SECONDS (fail-closed: absent → already expired). */
  expiresIn?: number;
}

/**
 * Maps a token response onto a {@link Session} using an injectable clock.
 * `expiresIn` absent ⇒ expiry 0 (immediately stale, forcing a refresh or
 * re-login) — the app never invents a lifetime.
 */
export function tokenResponseToSession(
  response: TokenResponseLike,
  nowMs: () => number = () => Date.now(),
): Session {
  const issuedAtMs = response.issuedAt * 1000;
  const expiresInMs = (response.expiresIn ?? 0) * 1000;
  const idToken = response.idToken ?? null;
  return {
    accessToken: response.accessToken,
    refreshToken: response.refreshToken ?? null,
    idToken,
    accessTokenExpiresAtMs: issuedAtMs + expiresInMs,
    claims: idToken !== null ? parseIdTokenClaims(idToken) : null,
  };
}

/**
 * Whether the access token is (or will soon be) expired, with a refresh
 * skew (default 30s) so requests are not launched with tokens that die
 * mid-flight.
 */
export function isSessionExpired(session: Session, nowMs?: () => number, skewMs = 30_000): boolean {
  const now = nowMs !== undefined ? nowMs() : Date.now();
  return now + skewMs >= session.accessTokenExpiresAtMs;
}

/** The session's principal display name (best effort). */
export function sessionDisplayName(session: Session): string {
  const claims = session.claims;
  if (claims === null) {
    return 'SharkPay user';
  }
  return claims.preferred_username ?? claims.name ?? claims.email ?? 'SharkPay user';
}

/** Console roles derived from realm_access.roles (client roles come later). */
export function extractRoles(session: Session): string[] {
  return session.claims?.realmAccess?.roles ?? [];
}
