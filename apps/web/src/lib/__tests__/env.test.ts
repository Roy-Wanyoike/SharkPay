import { afterEach, describe, expect, it, vi } from "vitest";
import {
  getApiBaseUrl,
  getAuthMode,
  getAuthSecret,
  getEnvironmentBadge,
  getKeycloakConfig,
} from "@/lib/env";

afterEach(() => {
  vi.unstubAllEnvs();
});

describe("getAuthMode", () => {
  it("defaults to keycloak (fail-safe) when unset", () => {
    expect(getAuthMode()).toBe("keycloak");
  });

  it("accepts mock and keycloak explicitly", () => {
    vi.stubEnv("AUTH_MODE", "mock");
    expect(getAuthMode()).toBe("mock");
    vi.stubEnv("AUTH_MODE", "keycloak");
    expect(getAuthMode()).toBe("keycloak");
  });

  it("rejects unknown values", () => {
    vi.stubEnv("AUTH_MODE", "sneaky");
    expect(getAuthMode()).toBe("keycloak");
  });
});

describe("getAuthSecret", () => {
  it("returns a provided secret", () => {
    vi.stubEnv("AUTH_SECRET", "0123456789abcdef0123456789abcdef");
    expect(getAuthSecret()).toBe("0123456789abcdef0123456789abcdef");
  });

  it("falls back to an insecure dev secret outside production", () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    expect(getAuthSecret()).toContain("dev");
    expect(warn).toHaveBeenCalled();
  });
});

describe("getApiBaseUrl", () => {
  it("defaults to the api-gateway port from docker-compose", () => {
    expect(getApiBaseUrl()).toBe("http://localhost:8088");
  });

  it("honours the env var and strips trailing slashes", () => {
    vi.stubEnv("NEXT_PUBLIC_API_BASE_URL", "https://api.sharkpay.dev/v1///");
    expect(getApiBaseUrl()).toBe("https://api.sharkpay.dev/v1");
  });
});

describe("getKeycloakConfig", () => {
  it("defaults to the dev realm and console client", () => {
    const config = getKeycloakConfig();
    expect(config).toEqual({
      url: "http://localhost:8080",
      realm: "sharkpay",
      clientId: "sharkpay-web",
    });
  });

  it("reads overrides and strips a trailing slash", () => {
    vi.stubEnv("NEXT_PUBLIC_KEYCLOAK_URL", "https://sso.sharkpay.dev/");
    vi.stubEnv("NEXT_PUBLIC_KEYCLOAK_REALM", "sharkpay-prod");
    vi.stubEnv("NEXT_PUBLIC_KEYCLOAK_CLIENT_ID", "sharkpay-web-prod");
    expect(getKeycloakConfig()).toEqual({
      url: "https://sso.sharkpay.dev",
      realm: "sharkpay-prod",
      clientId: "sharkpay-web-prod",
    });
  });
});

describe("getEnvironmentBadge", () => {
  it("defaults to sandbox", () => {
    expect(getEnvironmentBadge()).toBe("sandbox");
  });

  it("maps exactly 'prod' to prod", () => {
    vi.stubEnv("NEXT_PUBLIC_ENV", "prod");
    expect(getEnvironmentBadge()).toBe("prod");
    vi.stubEnv("NEXT_PUBLIC_ENV", "production");
    expect(getEnvironmentBadge()).toBe("sandbox");
  });
});
