import { describe, expect, it, vi } from "vitest";
import {
  buildAuthorizeUrl,
  buildEndSessionUrl,
  exchangeAuthorizationCode,
  extractRoles,
  oidcEndpoints,
  parseIdTokenClaims,
  refreshAccessToken,
} from "@/lib/auth/keycloak";
import type { KeycloakClientConfig } from "@/lib/env";

const CONFIG: KeycloakClientConfig = {
  url: "http://localhost:8080",
  realm: "sharkpay",
  clientId: "sharkpay-web",
};

const ENDPOINTS = oidcEndpoints(CONFIG);

describe("oidcEndpoints", () => {
  it("builds the standard realm protocol URLs", () => {
    expect(ENDPOINTS.authorize).toBe(
      "http://localhost:8080/realms/sharkpay/protocol/openid-connect/auth",
    );
    expect(ENDPOINTS.token).toBe(
      "http://localhost:8080/realms/sharkpay/protocol/openid-connect/token",
    );
    expect(ENDPOINTS.logout).toBe(
      "http://localhost:8080/realms/sharkpay/protocol/openid-connect/logout",
    );
  });

  it("trims trailing slashes from the base url and escapes the realm", () => {
    const endpoints = oidcEndpoints({ url: "http://sso.example.com/", realm: "shark pay", clientId: "c" });
    expect(endpoints.token).toBe(
      "http://sso.example.com/realms/shark%20pay/protocol/openid-connect/token",
    );
  });
});

describe("buildAuthorizeUrl", () => {
  it("encodes the authorization-code + PKCE S256 request", () => {
    const url = buildAuthorizeUrl({
      endpoints: ENDPOINTS,
      clientId: "sharkpay-web",
      redirectUri: "http://localhost:3000/api/auth/callback",
      state: "state-123",
      codeChallenge: "challenge-abc",
    });
    const parsed = new URL(url);
    expect(parsed.origin + parsed.pathname).toBe(ENDPOINTS.authorize);
    expect(parsed.searchParams.get("response_type")).toBe("code");
    expect(parsed.searchParams.get("client_id")).toBe("sharkpay-web");
    expect(parsed.searchParams.get("redirect_uri")).toBe("http://localhost:3000/api/auth/callback");
    expect(parsed.searchParams.get("state")).toBe("state-123");
    expect(parsed.searchParams.get("code_challenge")).toBe("challenge-abc");
    expect(parsed.searchParams.get("code_challenge_method")).toBe("S256");
    expect(parsed.searchParams.get("scope")).toBe("openid profile email");
  });
});

describe("buildEndSessionUrl", () => {
  it("includes id_token_hint and post-logout redirect", () => {
    const url = buildEndSessionUrl({
      endpoints: ENDPOINTS,
      clientId: "sharkpay-web",
      idTokenHint: "token-xyz",
      postLogoutRedirectUri: "http://localhost:3000/login",
    });
    const parsed = new URL(url);
    expect(parsed.origin + parsed.pathname).toBe(ENDPOINTS.logout);
    expect(parsed.searchParams.get("id_token_hint")).toBe("token-xyz");
    expect(parsed.searchParams.get("post_logout_redirect_uri")).toBe("http://localhost:3000/login");
    expect(parsed.searchParams.get("client_id")).toBe("sharkpay-web");
  });

  it("omits absent optional params", () => {
    const url = buildEndSessionUrl({ endpoints: ENDPOINTS, clientId: "sharkpay-web" });
    expect(new URL(url).searchParams.has("id_token_hint")).toBe(false);
  });
});

function b64url(input: string): string {
  return btoa(input).replace(/\+/g, "-").replace(/\//g, "_").replace(/=+$/, "");
}

function fakeIdToken(claims: object): string {
  return `${b64url(JSON.stringify({ alg: "RS256" }))}.${b64url(JSON.stringify(claims))}.sig`;
}

describe("token exchange", () => {
  it("posts the authorization-code grant with the PKCE verifier", async () => {
    const fetchImpl = vi.fn(async () =>
      new Response(
        JSON.stringify({
          access_token: "at",
          refresh_token: "rt",
          id_token: "it",
          expires_in: 300,
          token_type: "Bearer",
        }),
        { status: 200, headers: { "Content-Type": "application/json" } },
      ),
    );

    const tokens = await exchangeAuthorizationCode({
      tokenEndpoint: ENDPOINTS.token,
      clientId: "sharkpay-web",
      code: "the-code",
      redirectUri: "http://localhost:3000/api/auth/callback",
      codeVerifier: "the-verifier",
      fetchImpl: fetchImpl as unknown as typeof fetch,
    });

    expect(tokens).toMatchObject({ access_token: "at", expires_in: 300, refresh_token: "rt", id_token: "it" });

    const [url, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit];
    expect(url).toBe(ENDPOINTS.token);
    expect(init.method).toBe("POST");
    expect(init.headers).toMatchObject({ "Content-Type": "application/x-www-form-urlencoded" });
    const form = new URLSearchParams(init.body as string);
    expect(form.get("grant_type")).toBe("authorization_code");
    expect(form.get("client_id")).toBe("sharkpay-web");
    expect(form.get("code")).toBe("the-code");
    expect(form.get("code_verifier")).toBe("the-verifier");
    expect(form.get("redirect_uri")).toBe("http://localhost:3000/api/auth/callback");
  });

  it("throws on non-2xx token responses", async () => {
    const fetchImpl = vi.fn(async () => new Response("{}", { status: 400 }));
    await expect(
      exchangeAuthorizationCode({
        tokenEndpoint: ENDPOINTS.token,
        clientId: "sharkpay-web",
        code: "c",
        redirectUri: "r",
        codeVerifier: "v",
        fetchImpl: fetchImpl as unknown as typeof fetch,
      }),
    ).rejects.toThrow("400");
  });

  it("throws on malformed bodies", async () => {
    const fetchImpl = vi.fn(async () => new Response("{}", { status: 200 }));
    await expect(
      exchangeAuthorizationCode({
        tokenEndpoint: ENDPOINTS.token,
        clientId: "sharkpay-web",
        code: "c",
        redirectUri: "r",
        codeVerifier: "v",
        fetchImpl: fetchImpl as unknown as typeof fetch,
      }),
    ).rejects.toThrow("Malformed");
  });

  it("refresh uses the refresh_token grant", async () => {
    const fetchImpl = vi.fn(
      async () =>
        new Response(JSON.stringify({ access_token: "at2", expires_in: 60 }), {
          status: 200,
          headers: { "Content-Type": "application/json" },
        }),
    );
    const tokens = await refreshAccessToken({
      tokenEndpoint: ENDPOINTS.token,
      clientId: "sharkpay-web",
      refreshToken: "rt",
      fetchImpl: fetchImpl as unknown as typeof fetch,
    });
    expect(tokens.access_token).toBe("at2");
    const [, init] = fetchImpl.mock.calls[0] as unknown as [string, RequestInit];
    const form = new URLSearchParams(init.body as string);
    expect(form.get("grant_type")).toBe("refresh_token");
    expect(form.get("refresh_token")).toBe("rt");
  });
});

describe("parseIdTokenClaims", () => {
  it("decodes the payload claims", () => {
    const claims = parseIdTokenClaims(
      fakeIdToken({ sub: "user-1", name: "Amina", preferred_username: "amina", email: "a@x.dev" }),
    );
    expect(claims).toMatchObject({
      sub: "user-1",
      name: "Amina",
      preferred_username: "amina",
      email: "a@x.dev",
    });
  });

  it("returns null for malformed tokens", () => {
    expect(parseIdTokenClaims("not-a-jwt")).toBeNull();
    expect(parseIdTokenClaims("a.b")).toBeNull();
    expect(parseIdTokenClaims(`${b64url("{}")}.!!.sig`)).toBeNull();
  });

  it("returns null when sub is missing", () => {
    expect(parseIdTokenClaims(fakeIdToken({ name: "no-sub" }))).toBeNull();
  });
});

describe("extractRoles", () => {
  it("maps realm_access.roles", () => {
    expect(extractRoles({ sub: "u", realmAccess: { roles: ["ops", "finance"] } })).toEqual([
      "ops",
      "finance",
    ]);
  });

  it("defaults to no roles", () => {
    expect(extractRoles({ sub: "u" })).toEqual([]);
  });
});
