import {
  decodeText,
  encodeText,
  fromBase64Url,
  toBase64Url,
} from "@/lib/auth/encoding";

/**
 * Session cookie sealing — AES-256-GCM via Web Crypto only (no Node crypto,
 * so the Edge middleware can validate sessions too).
 *
 * Wire format: base64url( iv(12) || ciphertext||tag ), key = SHA-256(secret).
 * GCM gives confidentiality + integrity: any tamper or wrong secret fails
 * the tag check and unseal returns null.
 */

export const SESSION_COOKIE = "sharkpay_session";
/** Short-lived PKCE/state holder during the OIDC redirect dance. */
export const AUTH_FLOW_COOKIE = "sharkpay_auth_flow";

/** Session lifetime matches the access token TTL (seconds). */
export const SESSION_TTL_SECONDS = 8 * 60 * 60;
export const AUTH_FLOW_TTL_SECONDS = 10 * 60;

export interface SessionUser {
  sub: string;
  name: string;
  preferred_username: string;
  email?: string;
  roles: string[];
}

export interface Session {
  user: SessionUser;
  accessToken: string;
  refreshToken?: string;
  /** id_token retained for Keycloak post-logout redirects. */
  idToken?: string;
  /** Epoch seconds. */
  issuedAt: number;
  /** Epoch seconds. */
  expiresAt: number;
  mode: "keycloak" | "mock";
}

function isRecord(value: unknown): value is Record<string, unknown> {
  return typeof value === "object" && value !== null;
}

function isValidSession(value: unknown): value is Session {
  if (!isRecord(value)) return false;
  const user = value.user;
  if (
    !isRecord(user) ||
    typeof user.sub !== "string" ||
    typeof user.name !== "string" ||
    typeof user.preferred_username !== "string" ||
    !Array.isArray(user.roles) ||
    !user.roles.every((role) => typeof role === "string")
  ) {
    return false;
  }
  return (
    typeof value.accessToken === "string" &&
    typeof value.issuedAt === "number" &&
    typeof value.expiresAt === "number" &&
    (value.mode === "keycloak" || value.mode === "mock")
  );
}

async function deriveKey(secret: string): Promise<CryptoKey> {
  const digest = await crypto.subtle.digest("SHA-256", encodeText(secret));
  return crypto.subtle.importKey("raw", digest, { name: "AES-GCM" }, false, [
    "encrypt",
    "decrypt",
  ]);
}

export async function sealSession(session: Session, secret: string): Promise<string> {
  const iv = crypto.getRandomValues(new Uint8Array(12));
  const key = await deriveKey(secret);
  const ciphertext = await crypto.subtle.encrypt(
    { name: "AES-GCM", iv },
    key,
    encodeText(JSON.stringify(session)),
  );
  const payload = new Uint8Array(iv.length + ciphertext.byteLength);
  payload.set(iv);
  payload.set(new Uint8Array(ciphertext), iv.length);
  return toBase64Url(payload);
}

export async function unsealSession(sealed: string, secret: string): Promise<Session | null> {
  try {
    const payload = fromBase64Url(sealed);
    if (payload.length <= 12) return null;
    const iv = payload.slice(0, 12);
    const ciphertext = payload.slice(12);
    const key = await deriveKey(secret);
    const plaintext = await crypto.subtle.decrypt(
      { name: "AES-GCM", iv },
      key,
      ciphertext,
    );
    const parsed: unknown = JSON.parse(decodeText(new Uint8Array(plaintext)));
    return isValidSession(parsed) ? parsed : null;
  } catch {
    // Tampered cookie, wrong secret, or corrupt base64 — all equivalent:
    // no session.
    return null;
  }
}

/** True when the access token is within `skewMs` of (or past) expiry. */
export function isSessionExpired(session: Session, nowMs = Date.now(), skewMs = 30_000): boolean {
  return session.expiresAt * 1000 - skewMs <= nowMs;
}
