import type { IconName } from "@/components/layout/icons";

/** Sidebar navigation model (single source of truth for AppShell + Sidebar). */

export interface NavItem {
  href: string;
  label: string;
  icon: IconName;
  /** Optional short description for tooltips / a11y context. */
  description?: string;
}

export interface NavSection {
  /** Section heading (null = flat section). */
  title: string | null;
  items: NavItem[];
}

export const NAV_SECTIONS: readonly NavSection[] = [
  {
    title: null,
    items: [{ href: "/", label: "Dashboard", icon: "dashboard", description: "Operations overview" }],
  },
  {
    title: "Money movement",
    items: [
      { href: "/payments", label: "Payments", icon: "payments", description: "Inbound payment intents" },
      { href: "/payouts", label: "Payouts", icon: "payouts", description: "External withdrawals" },
      { href: "/wallets", label: "Wallets", icon: "wallets", description: "Balances and statements" },
      { href: "/fx", label: "FX", icon: "fx", description: "Quotes and conversions" },
    ],
  },
  {
    title: "Control",
    items: [
      { href: "/risk", label: "Risk Cases", icon: "risk", description: "Rule hits and investigations" },
      { href: "/api-keys", label: "API Keys", icon: "keys", description: "Scoped programmatic access" },
      { href: "/webhooks", label: "Webhooks", icon: "webhooks", description: "Event delivery endpoints" },
    ],
  },
  {
    title: null,
    items: [{ href: "/settings", label: "Settings", icon: "settings", description: "Console preferences" }],
  },
] as const;

export const NAV_ITEMS: readonly NavItem[] = NAV_SECTIONS.flatMap((section) => section.items);

export function isNavItemActive(pathname: string, href: string): boolean {
  if (href === "/") {
    return pathname === "/";
  }
  return pathname === href || pathname.startsWith(`${href}/`);
}
