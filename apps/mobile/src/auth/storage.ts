/**
 * Session persistence over expo-secure-store (Keychain / Android Keystore —
 * never plain-text storage and never localStorage).
 *
 * Corruption/absence never throws: `load()` returns `null` and the app
 * falls back to the Login screen.
 */

import * as SecureStore from 'expo-secure-store';

import type { Session, TokenStorage } from './types';

const STORAGE_KEY = 'sharkpay.session.v1';

/** The secure-store slice used here (mock-friendly structural seam). */
export interface SecureStoreLike {
  setItemAsync(key: string, value: string): Promise<void>;
  getItemAsync(key: string): Promise<string | null>;
  deleteItemAsync(key: string): Promise<void>;
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null && !Array.isArray(value);
}

/** Validates unknown JSON as a {@link Session}; `null` when malformed. */
export function parsePersistedSession(raw: unknown): Session | null {
  if (!isRecord(raw)) {
    return null;
  }
  const accessToken = raw['accessToken'];
  const refreshToken = raw['refreshToken'];
  const idToken = raw['idToken'];
  const expiresAt = raw['accessTokenExpiresAtMs'];
  if (
    typeof accessToken !== 'string' ||
    accessToken.length === 0 ||
    (refreshToken !== null && typeof refreshToken !== 'string') ||
    (idToken !== null && typeof idToken !== 'string') ||
    typeof expiresAt !== 'number' ||
    !Number.isFinite(expiresAt)
  ) {
    return null;
  }
  const claims = raw['claims'];
  const parsedClaims =
    isRecord(claims) && typeof claims['sub'] === 'string'
      ? {
          sub: claims['sub'],
          ...(typeof claims['name'] === 'string' ? { name: claims['name'] } : {}),
          ...(typeof claims['preferred_username'] === 'string'
            ? { preferred_username: claims['preferred_username'] }
            : {}),
          ...(typeof claims['email'] === 'string' ? { email: claims['email'] } : {}),
          ...(typeof claims['exp'] === 'number' ? { exp: claims['exp'] } : {}),
        }
      : null;
  return {
    accessToken,
    refreshToken: refreshToken ?? null,
    idToken: idToken ?? null,
    accessTokenExpiresAtMs: expiresAt,
    claims: parsedClaims,
  };
}

export class SecureTokenStorage implements TokenStorage {
  private readonly store: SecureStoreLike;
  private readonly key: string;

  constructor(store: SecureStoreLike = SecureStore, key: string = STORAGE_KEY) {
    this.store = store;
    this.key = key;
  }

  async save(session: Session): Promise<void> {
    await this.store.setItemAsync(this.key, JSON.stringify(session));
  }

  async load(): Promise<Session | null> {
    let raw: string | null;
    try {
      raw = await this.store.getItemAsync(this.key);
    } catch {
      return null;
    }
    if (raw === null) {
      return null;
    }
    try {
      return parsePersistedSession(JSON.parse(raw) as unknown);
    } catch {
      return null;
    }
  }

  async clear(): Promise<void> {
    try {
      await this.store.deleteItemAsync(this.key);
    } catch {
      // Clearing is idempotent from the caller's perspective.
    }
  }
}
