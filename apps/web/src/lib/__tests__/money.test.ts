import { describe, expect, it } from "vitest";
import {
  CURRENCY_EXPONENTS,
  currencyExponent,
  formatMinor,
  formatMoney,
  formatRate,
} from "@/lib/money";

describe("formatMinor", () => {
  it("formats exponent-2 minor units with grouping", () => {
    expect(formatMinor(150000, 2)).toBe("1,500.00");
    expect(formatMinor(15000000, 2)).toBe("150,000.00");
    expect(formatMinor(0, 2)).toBe("0.00");
    expect(formatMinor(5, 2)).toBe("0.05");
  });

  it("formats stablecoin exponent-6 amounts", () => {
    expect(formatMinor(1250000000, 6)).toBe("1,250.000000");
    expect(formatMinor(31250000, 6)).toBe("31.250000");
  });

  it("formats exponent-0 amounts without a fraction", () => {
    expect(formatMinor(9007199254740993n, 0)).toBe("9,007,199,254,740,993");
  });

  it("handles negatives and explicit signs exactly (no float math)", () => {
    expect(formatMinor(-150000, 2)).toBe("-1,500.00");
    expect(formatMinor(150000, 2, { withSign: true })).toBe("+1,500.00");
    // 2^53 + 1 is the canonical float-unsafe integer: only bigint keeps it
    // exact — the plain number literal 9007199254740993 parses as ...992.
    expect(formatMinor(9007199254740993n, 0)).not.toBe(formatMinor(9007199254740992, 0));
  });

  it("truncates fractional inputs defensively", () => {
    expect(formatMinor(150000.9, 2)).toBe("1,500.00");
  });
});

describe("formatMoney", () => {
  it("renders currency code and grouped amount", () => {
    expect(
      formatMoney({ amount_minor: 45000000, currency: "KES", exponent: 2 }),
    ).toBe("KES 450,000.00");
    expect(
      formatMoney({ amount_minor: 250000000, currency: "USDC", exponent: 6 }),
    ).toBe("USDC 250.000000");
  });
});

describe("formatRate", () => {
  it("renders integer-exact rates at their exponent", () => {
    expect(
      formatRate({ value_minor: 7719, exponent: 4, base_currency: "KES", quote_currency: "USD" }),
    ).toBe("0.7719");
    expect(
      formatRate({ value_minor: 1295286, exponent: 4, base_currency: "USD", quote_currency: "KES" }),
    ).toBe("129.5286");
    expect(
      formatRate({ value_minor: 150, exponent: 2, base_currency: "EUR", quote_currency: "USD" }),
    ).toBe("1.50");
  });
});

describe("currencyExponent", () => {
  it("maps the V1 currency table", () => {
    expect(CURRENCY_EXPONENTS.KES).toBe(2);
    expect(currencyExponent("USDC")).toBe(6);
    expect(currencyExponent("USD")).toBe(2);
  });
});
