import { describe, expect, it } from "vitest";
import { formatDateTime, formatDateTimeFull, formatPercent, maskTail, shortId } from "@/lib/format";

describe("formatDateTime", () => {
  it("renders UTC-qualified timestamps", () => {
    expect(formatDateTime("2026-09-03T10:04:41Z")).toMatch(/03 Sep.*UTC/);
  });

  it("returns a dash for invalid dates", () => {
    expect(formatDateTime("not-a-date")).toBe("—");
  });
});

describe("formatDateTimeFull", () => {
  it("includes the year", () => {
    expect(formatDateTimeFull("2026-09-03T10:04:41Z")).toMatch(/2026/);
    expect(formatDateTimeFull("oops")).toBe("—");
  });
});

describe("formatPercent", () => {
  it("formats with configurable digits", () => {
    expect(formatPercent(98.4)).toBe("98.4%");
    expect(formatPercent(96.16, 2)).toBe("96.16%");
  });
});

describe("shortId", () => {
  it("truncates long ids with an ellipsis", () => {
    expect(shortId("pay_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", 8)).toBe("pay_01HZ…");
  });

  it("keeps short ids intact", () => {
    expect(shortId("wal_123", 8)).toBe("wal_123");
  });
});

describe("maskTail", () => {
  it("masks all but the last characters", () => {
    expect(maskTail("+254712345678", 4)).toBe("••••••••5678");
    expect(maskTail("1122334455", 4)).toBe("••••••4455");
  });

  it("returns short values unchanged", () => {
    expect(maskTail("1234", 4)).toBe("1234");
  });
});
