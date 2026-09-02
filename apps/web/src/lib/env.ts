/**
 * Centralised, typed environment access.
 *
 * Rules:
 * - `NEXT_PUBLIC_*` values are referenced literally here so Next.js inlines
 *   them into both the server and client bundles.
 * - Server-only secrets (`AUTH_SECRET`, `AUTH_MODE`) are never imported by
 *   client components.
 * - Missing public values fall back to the dev-stack defaults documented in
 *   the repo root docker-compose.yml (api-gateway on 8088, Keycloak on 8080).
 */

export type AuthMode = "keycloak" | "mock";
export type EnvironmentBadge = "sandbox" | "prod";

export interface KeycloakClientConfig {
  /** Keycloak base URL, e.g. http://localhost:8080 — no trailing slash. */
  url: string;
  /** Realm name (sharkpay in dev). */
  realm: string;
  /** OIDC client id (sharkpay-web). */
  clientId: string;
}

export function getAuthMode(): AuthMode {
  const raw = process.env.AUTH_MODE;
  if (raw === "mock" || raw === "keycloak") {
    return raw;
  }
  // Fail-safe default: never silently downgrade to a mock session.
  return "keycloak";
}

export function getAuthSecret(): string {
  const secret = process.env.AUTH_SECRET;
  if (secret && secret.length >= 16) {
    return secret;
  }
  if (process.env.NODE_ENV === "production") {
    // Only reachable at request time (routes/middleware), never during
    // `next build` static analysis.
    throw new Error(
      "AUTH_SECRET must be set to at least 16 characters in production.",
    );
  }
  if (secret) {
    console.warn(
      "[env] AUTH_SECRET is shorter than 16 characters — using it anyway in non-production.",
    );
    return secret;
  }
  console.warn(
    "[env] AUTH_SECRET is not set — falling back to an insecure dev secret. Set it before enabling Keycloak mode.",
  );
  return "sharkpay-dev-insecure-secret";
}

export function getApiBaseUrl(): string {
  const url = process.env.NEXT_PUBLIC_API_BASE_URL;
  if (!url) return "http://localhost:8088";
  return url.replace(/\/+$/, "");
}

export function getKeycloakConfig(): KeycloakClientConfig {
  return {
    url: (process.env.NEXT_PUBLIC_KEYCLOAK_URL ?? "http://localhost:8080").replace(
      /\/+$/,
      "",
    ),
    realm: process.env.NEXT_PUBLIC_KEYCLOAK_REALM ?? "sharkpay",
    clientId: process.env.NEXT_PUBLIC_KEYCLOAK_CLIENT_ID ?? "sharkpay-web",
  };
}

export function getEnvironmentBadge(): EnvironmentBadge {
  return process.env.NEXT_PUBLIC_ENV === "prod" ? "prod" : "sandbox";
}
