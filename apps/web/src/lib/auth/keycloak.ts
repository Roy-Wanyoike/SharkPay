import type { KeycloakClientConfig } from "@/lib/env";

/**
 * Hand-rolled Keycloak OIDC helpers (authorization code + PKCE). The
 * sharkpay-web client is public (no client secret); tokens are only ever
 * exchanged server-side in route handlers over the redirect-back request.
 */

export interface OidcEndpoints {
  authorize: string;
  token: string;
  logout: string;
}

/** {url}/realms/{realm}/protocol/openid-connect/... */
export function oidcEndpoints(config: KeycloakClientConfig): OidcEndpoints {
  const base = `${config.url.replace(/\/+$/, "")}/realms/${encodeURIComponent(
    config.realm,
  )}/protocol/openid-connect`;
  return {
    authorize: `${base}/auth`,
    token: `${base}/token`,
    logout: `${base}/logout`,
  };
}

export interface AuthorizeUrlParams {
  endpoints: OidcEndpoints;
  clientId: string;
  redirectUri: string;
  state: string;
  codeChallenge: string;
  scope?: string;
}

/** Builds the authorization-code + PKCE S256 authorize URL. */
export function buildAuthorizeUrl(params: AuthorizeUrlParams): string {
  const url = new URL(params.endpoints.authorize);
  url.searchParams.set("response_type", "code");
  url.searchParams.set("client_id", params.clientId);
  url.searchParams.set("redirect_uri", params.redirectUri);
  url.searchParams.set("state", params.state);
  url.searchParams.set("code_challenge", params.codeChallenge);
  url.searchParams.set("code_challenge_method", "S256");
  url.searchParams.set("scope", params.scope ?? "openid profile email");
  return url.toString();
}

export interface EndSessionUrlParams {
  endpoints: OidcEndpoints;
  clientId: string;
  idTokenHint?: string;
  postLogoutRedirectUri?: string;
}

/** Keycloak end-session URL with post_logout_redirect_uri (realm attribute set). */
export function buildEndSessionUrl(params: EndSessionUrlParams): string {
  const url = new URL(params.endpoints.logout);
  if (params.idTokenHint) {
    url.searchParams.set("id_token_hint", params.idTokenHint);
  }
  if (params.postLogoutRedirectUri) {
    url.searchParams.set("post_logout_redirect_uri", params.postLogoutRedirectUri);
  }
  url.searchParams.set("client_id", params.clientId);
  return url.toString();
}

/** Token endpoint response (subset we consume). */
export interface TokenResponse {
  access_token: string;
  refresh_token?: string;
  id_token?: string;
  expires_in: number;
  token_type: string;
  scope?: string;
}

function toTokenResponse(body: unknown): TokenResponse {
  if (typeof body !== "object" || body === null) {
    throw new Error("Malformed token response from Keycloak.");
  }
  const record = body as Record<string, unknown>;
  const accessToken = record.access_token;
  const expiresIn = record.expires_in;
  if (typeof accessToken !== "string" || typeof expiresIn !== "number") {
    throw new Error("Malformed token response from Keycloak.");
  }
  return {
    access_token: accessToken,
    expires_in: expiresIn,
    refresh_token:
      typeof record.refresh_token === "string" ? record.refresh_token : undefined,
    id_token: typeof record.id_token === "string" ? record.id_token : undefined,
    token_type: typeof record.token_type === "string" ? record.token_type : "Bearer",
    scope: typeof record.scope === "string" ? record.scope : undefined,
  };
}

async function postForm(
  endpoint: string,
  form: Record<string, string>,
  fetchImpl: typeof fetch,
): Promise<TokenResponse> {
  const response = await fetchImpl(endpoint, {
    method: "POST",
    headers: { "Content-Type": "application/x-www-form-urlencoded" },
    body: new URLSearchParams(form).toString(),
  });
  if (!response.ok) {
    throw new Error(
      `Keycloak token endpoint returned ${response.status} ${response.statusText}.`,
    );
  }
  return toTokenResponse(await response.json());
}

export interface ExchangeCodeParams {
  tokenEndpoint: string;
  clientId: string;
  code: string;
  redirectUri: string;
  codeVerifier: string;
  fetchImpl?: typeof fetch;
}

/** Exchanges the authorization code (with the PKCE verifier) for tokens. */
export async function exchangeAuthorizationCode(
  params: ExchangeCodeParams,
): Promise<TokenResponse> {
  return postForm(
    params.tokenEndpoint,
    {
      grant_type: "authorization_code",
      client_id: params.clientId,
      code: params.code,
      redirect_uri: params.redirectUri,
      code_verifier: params.codeVerifier,
    },
    params.fetchImpl ?? fetch,
  );
}

export interface RefreshParams {
  tokenEndpoint: string;
  clientId: string;
  refreshToken: string;
  fetchImpl?: typeof fetch;
}

/** Refreshes an access token (server-side; the console keeps this for future use). */
export async function refreshAccessToken(params: RefreshParams): Promise<TokenResponse> {
  return postForm(
    params.tokenEndpoint,
    {
      grant_type: "refresh_token",
      client_id: params.clientId,
      refresh_token: params.refreshToken,
    },
    params.fetchImpl ?? fetch,
  );
}

/** Claims subset the console reads from the id_token. */
export interface IdTokenClaims {
  sub: string;
  name?: string;
  preferred_username?: string;
  email?: string;
  exp?: number;
  realmAccess?: { roles?: string[] };
}

/**
 * Decodes (does NOT verify the signature of) the id_token payload. The token
 * arrives directly from Keycloak's token endpoint over TLS in a route
 * handler, so trusting the server response — not verifying the JWT locally —
 * is the documented stance for this foundation.
 */
export function parseIdTokenClaims(idToken: string): IdTokenClaims | null {
  const parts = idToken.split(".");
  if (parts.length !== 3) return null;
  try {
    const json = atob(
      parts[1].replace(/-/g, "+").replace(/_/g, "/").padEnd(28, "="),
    );
    const claims: unknown = JSON.parse(json);
    if (typeof claims !== "object" || claims === null) {
      return null;
    }
    const record = claims as Record<string, unknown>;
    const sub = record.sub;
    if (typeof sub !== "string") {
      return null;
    }
    const realmAccess = record.realm_access;
    return {
      sub,
      name: typeof record.name === "string" ? record.name : undefined,
      preferred_username:
        typeof record.preferred_username === "string"
          ? record.preferred_username
          : undefined,
      email: typeof record.email === "string" ? record.email : undefined,
      exp: typeof record.exp === "number" ? record.exp : undefined,
      realmAccess:
        typeof realmAccess === "object" && realmAccess !== null
          ? {
              roles: Array.isArray((realmAccess as Record<string, unknown>).roles)
                ? ((realmAccess as Record<string, unknown>).roles as unknown[])
                    .filter((role): role is string => typeof role === "string")
                : undefined,
            }
          : undefined,
    };
  } catch {
    return null;
  }
}

/** Console roles derived from realm_access.roles (client roles come later). */
export function extractRoles(claims: IdTokenClaims): string[] {
  return claims.realmAccess?.roles ?? [];
}
