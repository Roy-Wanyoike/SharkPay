import type { Session } from "@/lib/auth/session";

/**
 * Mock-auth mode (AUTH_MODE=mock) — a local-dev session with no Keycloak.
 * The session is still sealed with AUTH_SECRET like a real one; only the
 * identity source is synthetic. NEVER used when AUTH_MODE is unset
 * (env.ts defaults to keycloak).
 */

export const MOCK_USER = {
  sub: "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d",
  name: "Amina Okonkwo",
  preferred_username: "amina@sharkpay.dev",
  email: "amina@sharkpay.dev",
  roles: ["ops", "payments:read", "payments:write", "webhooks:manage"],
} as const;

export function buildMockSession(nowSeconds = Math.floor(Date.now() / 1000)): Session {
  return {
    user: { ...MOCK_USER, roles: [...MOCK_USER.roles] },
    // A clearly-fake bearer token: the API layer would 401 it, which is the
    // honest signal that mock sessions cannot call the live API.
    accessToken: "mock-session-token",
    issuedAt: nowSeconds,
    expiresAt: nowSeconds + 8 * 60 * 60,
    mode: "mock",
  };
}
