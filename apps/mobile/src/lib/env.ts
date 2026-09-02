/**
 * Centralised, typed environment access.
 *
 * Expo inlines `EXPO_PUBLIC_*` values at bundle time (they are PUBLIC by
 * design — the mobile client is a public OIDC client, PKCE only, never a
 * secret). Missing values fall back to the dev-stack defaults documented in
 * the repo docker-compose: api-gateway on 8088 routing /v1/*, Keycloak on
 * 8080 with the `sharkpay` realm.
 */

import { makeRedirectUri } from 'expo-auth-session';

import type { KeycloakClientConfig } from '../auth/types';

export type EnvironmentBadge = 'sandbox' | 'prod';

export interface AppEnv {
  /** API base URL including the /v1 version path. */
  apiBaseUrl: string;
  /** Keycloak client coordinates (public client + PKCE). */
  auth: KeycloakClientConfig;
  /** Environment badge surfaced in Settings. */
  badge: EnvironmentBadge;
}

function trimmed(value: string | undefined): string | undefined {
  if (value === undefined) {
    return undefined;
  }
  const trimmedValue = value.trim();
  return trimmedValue.length === 0 ? undefined : trimmedValue;
}

export function getApiBaseUrl(): string {
  const url = trimmed(process.env.EXPO_PUBLIC_API_BASE_URL) ?? 'http://localhost:8088/v1';
  return url.replace(/\/+$/, '');
}

export function getKeycloakConfig(): KeycloakClientConfig {
  const url =
    trimmed(process.env.EXPO_PUBLIC_KEYCLOAK_URL) ?? 'http://localhost:8080';
  const realm = trimmed(process.env.EXPO_PUBLIC_KEYCLOAK_REALM) ?? 'sharkpay';
  const clientId =
    trimmed(process.env.EXPO_PUBLIC_KEYCLOAK_CLIENT_ID) ?? 'sharkpay-mobile';
  const redirectUri =
    trimmed(process.env.EXPO_PUBLIC_AUTH_REDIRECT_URI) ??
    // sharkpay-mobile://callback on dev/standalone builds (registered in the
    // dev realm); exp://… under Expo Go.
    makeRedirectUri({ scheme: 'sharkpay-mobile', path: 'callback' });
  return { url: url.replace(/\/+$/, ''), realm, clientId, redirectUri };
}

export function getEnvironmentBadge(): EnvironmentBadge {
  return process.env.EXPO_PUBLIC_ENV === 'prod' ? 'prod' : 'sandbox';
}

export function getEnv(): AppEnv {
  return {
    apiBaseUrl: getApiBaseUrl(),
    auth: getKeycloakConfig(),
    badge: getEnvironmentBadge(),
  };
}
