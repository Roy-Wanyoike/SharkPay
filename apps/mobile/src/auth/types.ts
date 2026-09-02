/**
 * Auth module types: the Session value object plus the two ports
 * (ADR 003 §3 style) the app consumes — an `AuthGateway` (Keycloak OIDC
 * implementation in src/auth/gateway.ts, in-tree fakes in tests) and a
 * `TokenStorage` (secure storage implementation in src/auth/storage.ts).
 */

/** Claims subset the wallet reads from the Keycloak id_token. */
export interface IdTokenClaims {
  sub: string;
  name?: string;
  preferred_username?: string;
  email?: string;
  /** Access-token expiry (epoch seconds) as claimed by the id_token. */
  exp?: number;
  realmAccess?: { roles?: string[] };
}

/** A persisted OIDC session (access token used as the API Bearer token). */
export interface Session {
  accessToken: string;
  /** Refresh token for silent renewal; `null` when the IdP issued none. */
  refreshToken: string | null;
  /** OIDC id_token — decoded (not verified) for display claims. */
  idToken: string | null;
  /** Epoch ms when the access token expires (fail-closed: unknown → 0). */
  accessTokenExpiresAtMs: number;
  /** Decoded id_token claims, when the id_token was present and parseable. */
  claims: IdTokenClaims | null;
}

/** Keycloak client coordinates (public client, PKCE only — never a secret). */
export interface KeycloakClientConfig {
  /** Keycloak base URL, e.g. http://localhost:8080 — no trailing slash. */
  url: string;
  /** Realm name (`sharkpay` in dev). */
  realm: string;
  /** OIDC client id (`sharkpay-mobile`). */
  clientId: string;
  /** Redirect URI registered in the realm (sharkpay-mobile://callback). */
  redirectUri: string;
}

/**
 * The auth port. Implemented by `KeycloakAuthGateway` (expo-auth-session,
 * authorization code + PKCE S256) and by in-tree fakes in tests/screens'
 * smoke suites.
 */
export interface AuthGateway {
  /**
   * Runs the full browser login (authorization code + PKCE) and resolves
   * with the resulting session. Rejects with {@link AuthCancelledError} when
   * the user dismisses the browser.
   */
  login(): Promise<Session>;
  /**
   * Exchanges the session's refresh token for a new session. Rejects when
   * there is no refresh token or the IdP refuses the refresh.
   */
  refresh(session: Session): Promise<Session>;
  /**
   * Best-effort token revocation (RFC 7009). Never rejects — clearing local
   * state is the caller's job regardless of the outcome.
   */
  revoke(session: Session): Promise<void>;
}

/** User dismissed the auth session (browser closed / cancel). */
export class AuthCancelledError extends Error {
  constructor(message = 'Sign-in was cancelled.') {
    super(message);
    this.name = 'AuthCancelledError';
  }
}

/** The OIDC flow failed before tokens were issued (provider or config error). */
export class AuthFlowError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'AuthFlowError';
  }
}

/** Session persistence port (implemented over expo-secure-store). */
export interface TokenStorage {
  save(session: Session): Promise<void>;
  /** The persisted session, or `null` when none/corrupt — never throws. */
  load(): Promise<Session | null>;
  clear(): Promise<void>;
}
