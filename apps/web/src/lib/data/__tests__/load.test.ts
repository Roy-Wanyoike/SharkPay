import { describe, expect, it, vi } from "vitest";
import { loadWithFallback } from "@/lib/data/load";

describe("loadWithFallback", () => {
  it("passes API data through with source 'api'", async () => {
    const api = vi.fn(async () => ({ items: [1, 2, 3] }));
    const seed = vi.fn(() => ({ items: [9] }));

    const result = await loadWithFallback("test", api, seed);

    expect(result).toEqual({ data: { items: [1, 2, 3] }, source: "api" });
    expect(seed).not.toHaveBeenCalled();
  });

  it("falls back to the seed on API failure with source 'seed'", async () => {
    const warn = vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const api = vi.fn(async () => {
      throw new Error("ECONNREFUSED");
    });
    const seed = vi.fn(() => ({ items: [9] }));

    const result = await loadWithFallback("test", api, seed);

    expect(result).toEqual({ data: { items: [9] }, source: "seed" });
    expect(seed).toHaveBeenCalledTimes(1);
    expect(warn).toHaveBeenCalledWith(
      expect.stringMatching(/\[console\] test: API unavailable/),
    );
  });

  it("falls back on non-Error rejections too", async () => {
    vi.spyOn(console, "warn").mockImplementation(() => undefined);
    const api = vi.fn(async () => Promise.reject(new Error("nope")));
    const result = await loadWithFallback("test", api, () => "seed-value");
    expect(result).toEqual({ data: "seed-value", source: "seed" });
  });
});
