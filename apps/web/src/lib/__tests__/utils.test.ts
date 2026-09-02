import { describe, expect, it } from "vitest";
import { cn } from "@/lib/utils";

describe("cn", () => {
  it("joins truthy class names", () => {
    expect(cn("a", "b", "c")).toBe("a b c");
  });

  it("drops false/null/undefined/empty entries", () => {
    expect(cn("a", false, null, undefined, "", "b")).toBe("a b");
  });
});
