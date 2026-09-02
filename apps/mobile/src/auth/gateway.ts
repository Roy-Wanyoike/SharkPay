/**
 * `KeycloakAuthGateway` — the real `AuthGateway` port implementation.
 *
 * Uses expo-auth-session end to end, per the realm config in
 * infrastructure/dev/keycloak/sharkpay-realm.json (client `sharkpay-mobile`,
 * public, authorization-code + PKCE S256, redirects
 * `sharkpay-mobile://callback` and the Expo Go dev URIs):
 *
 * - login: `new AuthRequest({ clientId, redirectUri, scopes, usePKCE: true })`
 *   → `promptAsync(discovery)` opens the system browser/ASWebAuthenticationSession
 *   → `exchangeCodeAsync({ code, codeVerifier }, discovery)` (the PKCE
 *   verifier travels only over the token-endpoint TLS channel).
 * - refresh: `refreshAsync({ clientId, refreshToken }, discovery)`.
 * - revoke: `revokeAsync({ clientId, token }, discovery)` — best effort.
 *
 * The class is deliberately thin: every pure decision (endpoints, claims,
 * expiry) lives in src/auth/keycloak.ts where it is unit-tested.
 */

import {
  AuthRequest,
  exchangeCodeAsync,
  refreshAsync,
  revokeAsync,
  type DiscoveryDocument,
} from 'expo-auth-session';

import {
  discoveryDocument,
  tokenResponseToSession,
  type TokenResponseLike,
} from './keycloak';
import { AuthCancelledError, AuthFlowError, type AuthGateway, type KeycloakClientConfig, type Session } from './types';

/** Clock seam for deterministic behaviour (default: wall clock). */
export type NowMs = () => number;

export interface KeycloakGatewayOptions {
  config: KeycloakClientConfig;
  /** OIDC scopes requested (Keycloak default set for the console claims). */
  scopes?: string[];
  nowMs?: NowMs;
  /**
   * Escape hatch for tests / alternative runtimes: replaces the
   * expo-auth-session call surface. Not used in production wiring.
   */
  sessionModule?: AuthSessionModule;
}

/**
 * The slice of expo-auth-session the gateway uses — mirrored as a structural
 * interface so unit tests can substitute a fake without touching native
 * browser modules.
 */
export interface AuthSessionModule {
  createRequest(config: {
    clientId: string;
    redirectUri: string;
    scopes: string[];
    usePKCE: boolean;
  }): Promise<PromptableRequest>;
  exchangeCode(params: {
    clientId: string;
    code: string;
    redirectUri: string;
    codeVerifier: string;
  }): Promise<TokenResponseLike>;
  refresh(params: { clientId: string; refreshToken: string }): Promise<TokenResponseLike>;
  revoke(params: { clientId: string; token: string }): Promise<boolean>;
}

/** An `AuthRequest`-shaped object that can prompt the user. */
export interface PromptableRequest {
  codeVerifier: string | undefined;
  promptAsync(discovery: DiscoveryDocument): Promise<AuthSessionResultLike>;
}

/** An `AuthSessionResult`-shaped object (expo-auth-session union, slimmed). */
export type AuthSessionResultLike =
  | { type: 'cancel' }
  | { type: 'dismiss' }
  | { type: 'opened' }
  | { type: 'locked' }
  | { type: 'error'; params?: Record<string, string>; error?: { description?: string | null } | null }
  | { type: 'success'; params: Record<string, string> };

/** The default module — the real expo-auth-session surface. */
function expoAuthSessionModule(config: KeycloakClientConfig): AuthSessionModule {
  const discovery = discoveryDocument(config);
  return {
    createRequest: async (requestConfig) => {
      const request = new AuthRequest({
        clientId: requestConfig.clientId,
        redirectUri: requestConfig.redirectUri,
        scopes: requestConfig.scopes,
        // PKCE S256 is the default; explicit here because the realm pins it
        // (pkce.code.challenge.method: S256) and a public client must never
        // silently fall back to plain.
        usePKCE: true,
      });
      // NB: `codeVerifier` is only populated when promptAsync builds the
      // authorize URL (ensureCodeIsSetupAsync) — a getter keeps the wrapper
      // honest instead of snapshotting `undefined` at creation time.
      const wrapped: PromptableRequest = {
        get codeVerifier() {
          return request.codeVerifier;
        },
        promptAsync: (promptDiscovery) => request.promptAsync(promptDiscovery),
      };
      return wrapped;
    },
    exchangeCode: async (params) =>
      exchangeCodeAsync(
        {
          clientId: params.clientId,
          code: params.code,
          redirectUri: params.redirectUri,
          // AccessTokenRequestConfig has no first-class PKCE field — the
          // code_verifier travels as an extra body param (merged by
          // expo-auth-session's TokenRequest.getQueryBody).
          extraParams: { code_verifier: params.codeVerifier },
        },
        { tokenEndpoint: discovery.tokenEndpoint },
      ),
    refresh: async (params) =>
      refreshAsync(
        { clientId: params.clientId, refreshToken: params.refreshToken },
        { tokenEndpoint: discovery.tokenEndpoint },
      ),
    revoke: async (params) =>
      revokeAsync(
        { clientId: params.clientId, token: params.token },
        { revocationEndpoint: discovery.revocationEndpoint },
      ),
  };
}

export class KeycloakAuthGateway implements AuthGateway {
  private readonly config: KeycloakClientConfig;
  private readonly scopes: string[];
  private readonly nowMs: NowMs;
  private readonly sessionModule: AuthSessionModule;
  private readonly discovery: DiscoveryDocument;

  constructor(options: KeycloakGatewayOptions) {
    this.config = options.config;
    this.scopes = options.scopes ?? ['openid', 'profile', 'email'];
    this.nowMs = options.nowMs ?? (() => Date.now());
    this.sessionModule = options.sessionModule ?? expoAuthSessionModule(options.config);
    this.discovery = discoveryDocument(options.config);
  }

  async login(): Promise<Session> {
    const request = await this.sessionModule.createRequest({
      clientId: this.config.clientId,
      redirectUri: this.config.redirectUri,
      scopes: this.scopes,
      usePKCE: true,
    });
    const result = await request.promptAsync(this.discovery);

    if (result.type === 'cancel' || result.type === 'dismiss') {
      throw new AuthCancelledError(
        result.type === 'cancel' ? 'Sign-in was cancelled.' : 'Sign-in was dismissed.',
      );
    }
    if (result.type === 'opened' || result.type === 'locked') {
      throw new AuthFlowError(
        'The sign-in browser session did not complete (opened/locked without a result).',
      );
    }
    if (result.type === 'error') {
      const description = result.error?.description ?? 'unknown_error';
      throw new AuthFlowError(`Keycloak sign-in failed: ${description}`);
    }

    const code = result.params['code'] ?? '';
    const codeVerifier = request.codeVerifier ?? '';
    if (code.length === 0 || codeVerifier.length === 0) {
      throw new AuthFlowError(
        'The authorization response is missing the code or the PKCE verifier.',
      );
    }

    let tokenResponse: TokenResponseLike;
    try {
      tokenResponse = await this.sessionModule.exchangeCode({
        clientId: this.config.clientId,
        code,
        redirectUri: this.config.redirectUri,
        codeVerifier,
      });
    } catch (error) {
      throw new AuthFlowError(
        `Token exchange failed: ${error instanceof Error ? error.message : String(error)}`,
      );
    }
    return tokenResponseToSession(tokenResponse, this.nowMs);
  }

  async refresh(session: Session): Promise<Session> {
    if (session.refreshToken === null || session.refreshToken.length === 0) {
      throw new AuthFlowError('Cannot refresh: the session has no refresh token.');
    }
    let tokenResponse: TokenResponseLike;
    try {
      tokenResponse = await this.sessionModule.refresh({
        clientId: this.config.clientId,
        refreshToken: session.refreshToken,
      });
    } catch (error) {
      throw new AuthFlowError(
        `Token refresh failed: ${error instanceof Error ? error.message : String(error)}`,
      );
    }
    return tokenResponseToSession(tokenResponse, this.nowMs);
  }

  async revoke(session: Session): Promise<void> {
    const token = session.refreshToken ?? session.accessToken;
    try {
      await this.sessionModule.revoke({ clientId: this.config.clientId, token });
    } catch {
      // Best effort (RFC 7009): the caller clears local state regardless.
    }
  }
}
