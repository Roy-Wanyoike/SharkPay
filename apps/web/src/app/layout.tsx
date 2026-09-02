import type { Metadata, Viewport } from "next";
import { ToastProvider } from "@/components/ui/Toast";
import "./globals.css";

export const metadata: Metadata = {
  title: {
    default: "SharkPay Console",
    template: "%s — SharkPay Console",
  },
  description:
    "SharkPay operations console — payments, payouts, wallets, FX, risk and developer tooling.",
};

export const viewport: Viewport = {
  themeColor: "#060b18",
};

/**
 * Applies the persisted theme before first paint to avoid a flash of the
 * wrong palette. Kept tiny and inline (CSP-friendly: no eval, no remote).
 */
const THEME_INIT_SCRIPT = `try{var t=localStorage.getItem("sharkpay-theme");if(t==="light"||t==="dark"){document.documentElement.dataset.theme=t}}catch(e){}`;

export default function RootLayout({ children }: Readonly<{ children: React.ReactNode }>) {
  return (
    <html lang="en" data-theme="dark" suppressHydrationWarning>
      <head>
        <script dangerouslySetInnerHTML={{ __html: THEME_INIT_SCRIPT }} />
      </head>
      <body className="min-h-dvh bg-background font-sans text-fg antialiased">
        <ToastProvider>{children}</ToastProvider>
      </body>
    </html>
  );
}
