import { ApiClient } from "@/lib/api/client";
import type { Session } from "@/lib/auth/session";

/**
 * Server-side ApiClient factory: injects the session's access token as the
 * Bearer credential. In mock mode the token is a clearly-fake value, so the
 * API layer 401s and the seed fallback kicks in — exactly the honest
 * behaviour we want until Keycloak + the API gateway are wired together.
 */
export function apiClientForSession(session: Session): ApiClient {
  return new ApiClient({ accessToken: session.accessToken });
}
