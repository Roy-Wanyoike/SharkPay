import { describe, expect, it } from "vitest";
import { buildMockSession, MOCK_USER } from "@/lib/auth/mock";
import { isSessionExpired } from "@/lib/auth/session";

describe("buildMockSession", () => {
  it("creates a mock-mode session with the documented dev user", () => {
    const session = buildMockSession(1_000);
    expect(session.mode).toBe("mock");
    expect(session.user).toEqual({ ...MOCK_USER });
    expect(session.accessToken).toBe("mock-session-token");
    expect(session.issuedAt).toBe(1_000);
    expect(session.expiresAt).toBe(1_000 + 8 * 60 * 60);
  });

  it("is not expired immediately", () => {
    const session = buildMockSession();
    expect(isSessionExpired(session)).toBe(false);
  });

  it("carries no refresh/id tokens (nothing to refresh against)", () => {
    const session = buildMockSession(0);
    expect(session.refreshToken).toBeUndefined();
    expect(session.idToken).toBeUndefined();
  });
});
