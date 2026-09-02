import type { SVGProps } from "react";
import { cn } from "@/lib/utils";

/**
 * Hand-rolled stroke icon set (no external icon library — ADR 003 discipline
 * of keeping the console dependency-light). 24×24, currentColor, 1.75 stroke.
 */

export type IconName =
  | "dashboard"
  | "payments"
  | "payouts"
  | "wallets"
  | "fx"
  | "risk"
  | "keys"
  | "webhooks"
  | "settings"
  | "menu"
  | "close"
  | "chevron-down"
  | "chevron-right"
  | "check"
  | "alert"
  | "info"
  | "logout"
  | "user"
  | "sun"
  | "moon"
  | "external"
  | "spinner";

export type { SVGProps };

const PATHS: Record<IconName, React.ReactNode> = {
  dashboard: (
    <>
      <rect x="3" y="3" width="7" height="9" rx="1.5" />
      <rect x="14" y="3" width="7" height="5" rx="1.5" />
      <rect x="14" y="12" width="7" height="9" rx="1.5" />
      <rect x="3" y="16" width="7" height="5" rx="1.5" />
    </>
  ),
  payments: (
    <>
      <path d="M12 3v10m0 0 3.5-3.5M12 13 8.5 9.5" />
      <path d="M4 15v3.5A2.5 2.5 0 0 0 6.5 21h11a2.5 2.5 0 0 0 2.5-2.5V15" />
    </>
  ),
  payouts: (
    <>
      <path d="M12 21V11m0 0 3.5 3.5M12 11 8.5 14.5" />
      <path d="M4 9V5.5A2.5 2.5 0 0 1 6.5 3h11A2.5 2.5 0 0 1 20 5.5V9" />
    </>
  ),
  wallets: (
    <>
      <path d="M3 7.5A2.5 2.5 0 0 1 5.5 5h11A2.5 2.5 0 0 1 19 7.5v9A2.5 2.5 0 0 1 16.5 19h-11A2.5 2.5 0 0 1 3 16.5v-9Z" />
      <path d="M16 12h5v4h-5a2 2 0 0 1 0-4Z" />
    </>
  ),
  fx: (
    <>
      <path d="M4 8h13l-3-3M20 16H7l3 3" />
    </>
  ),
  risk: (
    <>
      <path d="M12 3 4.5 6v5c0 4.6 3.2 8.2 7.5 10 4.3-1.8 7.5-5.4 7.5-10V6L12 3Z" />
      <path d="M12 9v4M12 16.5v.01" />
    </>
  ),
  keys: (
    <>
      <circle cx="8" cy="15" r="4" />
      <path d="M10.8 12.2 20 3m-4 1 3 3m-5 1 2.5 2.5" />
    </>
  ),
  webhooks: (
    <>
      <path d="M13 3 4 14h6l-1 7 9-11h-6l1-7Z" />
    </>
  ),
  settings: (
    <>
      <circle cx="12" cy="12" r="3.25" />
      <path d="M12 2.75v2.5M12 18.75v2.5M4.13 7.3l2.16 1.25M17.71 15.45l2.16 1.25M4.13 16.7l2.16-1.25M17.71 8.55l2.16-1.25" />
    </>
  ),
  menu: (
    <>
      <path d="M4 6.5h16M4 12h16M4 17.5h16" />
    </>
  ),
  close: (
    <>
      <path d="M6 6l12 12M18 6 6 18" />
    </>
  ),
  "chevron-down": (
    <>
      <path d="m6 9 6 6 6-6" />
    </>
  ),
  "chevron-right": (
    <>
      <path d="m9 6 6 6-6 6" />
    </>
  ),
  check: (
    <>
      <path d="m5 12.5 4.5 4.5L19 7.5" />
    </>
  ),
  alert: (
    <>
      <path d="M12 4 2.75 20h18.5L12 4Z" />
      <path d="M12 10v4M12 17v.01" />
    </>
  ),
  info: (
    <>
      <circle cx="12" cy="12" r="9" />
      <path d="M12 11v5M12 8v.01" />
    </>
  ),
  logout: (
    <>
      <path d="M14 4h3.5A2.5 2.5 0 0 1 20 6.5v11a2.5 2.5 0 0 1-2.5 2.5H14" />
      <path d="M10 8 6 12l4 4M6 12h9" />
    </>
  ),
  user: (
    <>
      <circle cx="12" cy="8" r="4" />
      <path d="M4.5 20.5c1.6-3.4 4.3-5 7.5-5s5.9 1.6 7.5 5" />
    </>
  ),
  sun: (
    <>
      <circle cx="12" cy="12" r="4" />
      <path d="M12 2.5v2M12 19.5v2M2.5 12h2M19.5 12h2M5.2 5.2l1.4 1.4M17.4 17.4l1.4 1.4M5.2 18.8l1.4-1.4M17.4 6.6l1.4-1.4" />
    </>
  ),
  moon: (
    <>
      <path d="M20 14.5A8.5 8.5 0 0 1 9.5 4 8.5 8.5 0 1 0 20 14.5Z" />
    </>
  ),
  external: (
    <>
      <path d="M14 4h6v6M20 4l-9 9" />
      <path d="M19 14v4.5A2.5 2.5 0 0 1 16.5 21h-11A2.5 2.5 0 0 1 3 18.5v-11A2.5 2.5 0 0 1 5.5 5H10" />
    </>
  ),
  spinner: (
    <>
      <path d="M12 3a9 9 0 1 0 9 9" />
    </>
  ),
};

export interface IconProps extends Omit<SVGProps<SVGSVGElement>, "name" | "children"> {
  name: IconName;
  /** Pixel size (width/height); default 20. */
  size?: number;
}

export function Icon({ name, size = 20, className, ...rest }: IconProps) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 24 24"
      fill="none"
      stroke="currentColor"
      strokeWidth={1.75}
      strokeLinecap="round"
      strokeLinejoin="round"
      aria-hidden="true"
      focusable="false"
      className={cn("shrink-0", className)}
      {...rest}
    >
      {PATHS[name]}
    </svg>
  );
}

/** SharkPay wordless mark — a stylised shark fin over a swell. */
export function SharkpayMark({ size = 28, className }: { size?: number; className?: string }) {
  return (
    <svg
      width={size}
      height={size}
      viewBox="0 0 32 32"
      fill="none"
      aria-hidden="true"
      focusable="false"
      className={cn("shrink-0", className)}
    >
      <circle cx="16" cy="16" r="15" stroke="currentColor" strokeWidth="1.75" opacity="0.35" />
      <path
        d="M8 23c3-1.5 5.5-6 5.5-11 2 2.5 4 4 7 4.5-1 3-2.5 5-4.5 6.5"
        stroke="currentColor"
        strokeWidth="1.75"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
      <path
        d="M6.5 26.5c6-2 13-2 19 0"
        stroke="currentColor"
        strokeWidth="1.75"
        strokeLinecap="round"
        opacity="0.55"
      />
    </svg>
  );
}
