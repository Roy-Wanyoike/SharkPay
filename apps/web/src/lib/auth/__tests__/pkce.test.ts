import { describe, expect, it } from "vitest";
import { createPkcePair, deriveChallenge, randomState } from "@/lib/auth/pkce";
import { toBase64Url } from "@/lib/auth/encoding";

describe("createPkcePair", () => {
  it("produces a 43-char base64url verifier (32 random bytes)", async () => {
    const { verifier, challenge } = await createPkcePair();
    expect(verifier).toMatch(/^[A-Za-z0-9_-]{43}$/);
    expect(challenge).toMatch(/^[A-Za-z0-9_-]{43}$/);
  });

  it("challenge = base64url(SHA-256(verifier)) — RFC 7636 S256", async () => {
    const { verifier, challenge } = await createPkcePair();
    const digest = await crypto.subtle.digest("SHA-256", new TextEncoder().encode(verifier));
    expect(challenge).toBe(toBase64Url(new Uint8Array(digest)));
  });

  it("deriveChallenge is stable and matches createPkcePair", async () => {
    const { verifier, challenge } = await createPkcePair();
    expect(await deriveChallenge(verifier)).toBe(challenge);
  });

  it("generates distinct pairs", async () => {
    const first = await createPkcePair();
    const second = await createPkcePair();
    expect(first.verifier).not.toBe(second.verifier);
    expect(first.challenge).not.toBe(second.challenge);
  });
});

describe("randomState", () => {
  it("produces 32-char opaque base64url values", () => {
    const state = randomState();
    expect(state).toMatch(/^[A-Za-z0-9_-]{32}$/);
  });

  it("is unique per call", () => {
    const states = new Set(Array.from({ length: 50 }, () => randomState()));
    expect(states.size).toBe(50);
  });
});
