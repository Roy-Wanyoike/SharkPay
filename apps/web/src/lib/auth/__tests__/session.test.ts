import { describe, expect, it } from "vitest";
import {
  isSessionExpired,
  sealSession,
  unsealSession,
  SESSION_COOKIE,
  AUTH_FLOW_COOKIE,
  type Session,
} from "@/lib/auth/session";

const SECRET = "test-secret-0123456789abcdef";

function buildSession(overrides: Partial<Session> = {}): Session {
  const now = Math.floor(Date.now() / 1000);
  return {
    user: {
      sub: "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d",
      name: "Amina Okonkwo",
      preferred_username: "amina@sharkpay.dev",
      email: "amina@sharkpay.dev",
      roles: ["ops", "payments:read"],
    },
    accessToken: "token-value",
    issuedAt: now,
    expiresAt: now + 3600,
    mode: "keycloak",
    ...overrides,
  };
}

describe("session seal/unseal", () => {
  it("round-trips a full session", async () => {
    const session = buildSession({ refreshToken: "r", idToken: "i" });
    const sealed = await sealSession(session, SECRET);
    expect(typeof sealed).toBe("string");

    const restored = await unsealSession(sealed, SECRET);
    expect(restored).toEqual(session);
  });

  it("produces non-deterministic ciphertext (random IV per seal)", async () => {
    const session = buildSession();
    const first = await sealSession(session, SECRET);
    const second = await sealSession(session, SECRET);
    expect(first).not.toBe(second);
  });

  it("returns null for a wrong secret", async () => {
    const sealed = await sealSession(buildSession(), SECRET);
    expect(await unsealSession(sealed, "wrong-secret-entirely")).toBeNull();
  });

  it("returns null for tampered ciphertext", async () => {
    const sealed = await sealSession(buildSession(), SECRET);
    const flipped =
      sealed.slice(0, sealed.length - 2) +
      (sealed.endsWith("A") ? "B" : "A") +
      (sealed.endsWith("9") ? "8" : "9");
    expect(await unsealSession(flipped, SECRET)).toBeNull();
  });

  it("returns null for garbage and short inputs", async () => {
    expect(await unsealSession("not-base64url!!!", SECRET)).toBeNull();
    expect(await unsealSession("AAAA", SECRET)).toBeNull();
    expect(await unsealSession("", SECRET)).toBeNull();
  });

  it("rejects structurally invalid decrypted payloads", async () => {
    // Seal a payload that is valid base64url/JSON but not a Session.
    const sealed = await sealSession(buildSession(), SECRET);
    expect(sealed.length).toBeGreaterThan(0);
    // Hand-craft a sealed value of JSON {foo: 1}:
    const iv = crypto.getRandomValues(new Uint8Array(12));
    const key = await crypto.subtle.importKey(
      "raw",
      await crypto.subtle.digest("SHA-256", new TextEncoder().encode(SECRET)),
      { name: "AES-GCM" },
      false,
      ["encrypt"],
    );
    const ciphertext = await crypto.subtle.encrypt(
      { name: "AES-GCM", iv },
      key,
      new TextEncoder().encode(JSON.stringify({ foo: 1 })),
    );
    const payload = new Uint8Array(iv.length + ciphertext.byteLength);
    payload.set(iv);
    payload.set(new Uint8Array(ciphertext), iv.length);
    let binary = "";
    for (const byte of payload) binary += String.fromCharCode(byte);
    const fakeSealed = btoa(binary)
      .replace(/\+/g, "-")
      .replace(/\//g, "_")
      .replace(/=+$/, "");
    expect(await unsealSession(fakeSealed, SECRET)).toBeNull();
  });
});

describe("isSessionExpired", () => {
  it("treats a comfortably-future expiry as valid", () => {
    expect(isSessionExpired(buildSession({ expiresAt: Math.floor(Date.now() / 1000) + 600 }))).toBe(false);
  });

  it("applies a 30s clock-skew window", () => {
    const now = Math.floor(Date.now() / 1000);
    expect(isSessionExpired(buildSession({ expiresAt: now + 60 }))).toBe(false);
    expect(isSessionExpired(buildSession({ expiresAt: now + 29 }))).toBe(true);
    expect(isSessionExpired(buildSession({ expiresAt: now }))).toBe(true);
  });
});

describe("cookie names", () => {
  it("pins the documented cookie names", () => {
    expect(SESSION_COOKIE).toBe("sharkpay_session");
    expect(AUTH_FLOW_COOKIE).toBe("sharkpay_auth_flow");
  });
});
