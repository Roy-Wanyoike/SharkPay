import {
  randomUrlSafeBytes,
  toBase64Url,
  encodeText,
} from "@/lib/auth/encoding";

/**
 * PKCE (RFC 7636, S256) — hand-rolled with crypto.subtle, matching the
 * sharkpay-web client's enforced `pkce.code.challenge.method: S256`
 * (infrastructure/dev/keycloak/sharkpay-realm.json).
 */

export interface PkcePair {
  /** 43-char code_verifier (base64url of 32 random bytes). */
  verifier: string;
  /** base64url(SHA-256(verifier)) — 43 chars. */
  challenge: string;
}

export async function createPkcePair(): Promise<PkcePair> {
  const verifier = toBase64Url(randomUrlSafeBytes(32));
  const challenge = await deriveChallenge(verifier);
  return { verifier, challenge };
}

export async function deriveChallenge(verifier: string): Promise<string> {
  const digest = await crypto.subtle.digest("SHA-256", encodeText(verifier));
  return toBase64Url(new Uint8Array(digest));
}

/** Opaque state value binding the redirect to this browser. */
export function randomState(): string {
  return toBase64Url(randomUrlSafeBytes(24));
}
