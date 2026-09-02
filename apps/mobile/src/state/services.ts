/**
 * Service wiring: composes the real (production) or injected (tests/README
 * "mock vs real") implementations behind one `AppServices` object.
 *
 * The API client's token seam reads the LIVE session through
 * `deps.getSession()` (a mutable ref kept in sync by every auth action), so
 * a mid-flight refresh is picked up by the 401 replay without React
 * re-rendering anything.
 */

import { createSharkPayApi, type SharkPayApi } from '../api';
import { SharkPayApiClient, type FetchLike } from '../api/client';
import { KeycloakAuthGateway } from '../auth/gateway';
import { SecureTokenStorage } from '../auth/storage';
import type { AuthGateway, Session, TokenStorage } from '../auth/types';
import { getEnv, type AppEnv } from '../lib/env';

/** Everything the app does beyond rendering. */
export interface AppServices {
  env: AppEnv;
  auth: AuthGateway;
  storage: TokenStorage;
  api: SharkPayApi;
}

/** Test/override seams; production wiring uses the env-derived defaults. */
export interface ServiceOverrides {
  env?: AppEnv;
  fetchImpl?: FetchLike;
  auth?: AuthGateway;
  storage?: TokenStorage;
  nowMs?: () => number;
}

export interface CreateServicesDeps extends ServiceOverrides {
  /** Live session accessor (ref-backed; called per request attempt). */
  getSession: () => Session | null;
  /** Invoked after a successful silent refresh (session persisted). */
  onSessionRefreshed: (session: Session) => void;
}

export function createServices(deps: CreateServicesDeps): AppServices {
  const env = deps.env ?? getEnv();
  const auth =
    deps.auth ??
    new KeycloakAuthGateway({
      config: env.auth,
      ...(deps.nowMs !== undefined ? { nowMs: deps.nowMs } : {}),
    });
  const storage = deps.storage ?? new SecureTokenStorage();

  const apiClient = new SharkPayApiClient({
    baseUrl: env.apiBaseUrl,
    ...(deps.fetchImpl !== undefined ? { fetchImpl: deps.fetchImpl } : {}),
    getToken: () => deps.getSession()?.accessToken ?? null,
    refreshOnUnauthorized: async () => {
      const current = deps.getSession();
      if (current === null) {
        return null;
      }
      try {
        const refreshed = await auth.refresh(current);
        await storage.save(refreshed);
        deps.onSessionRefreshed(refreshed);
        return refreshed.accessToken;
      } catch {
        // Refresh refused (expired/revoked refresh token) — surface the
        // original 401 as AuthError so the app routes to Login.
        return null;
      }
    },
  });

  return {
    env,
    auth,
    storage,
    api: createSharkPayApi(apiClient),
  };
}
