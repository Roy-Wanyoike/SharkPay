"use client";

import { useEffect, useRef, useState, useSyncExternalStore, type ReactNode } from "react";
import { Icon } from "@/components/layout/icons";
import { cn } from "@/lib/utils";

export interface ThemeToggleProps {
  className?: string;
}

const STORAGE_KEY = "sharkpay-theme";

export function isStoredTheme(value: string | null | undefined): value is "light" | "dark" {
  return value === "light" || value === "dark";
}

/**
 * Light/dark switch persisted to localStorage. The pre-hydration script in
 * the root layout applies the stored value before first paint; this toggle
 * flips it live.
 */
/**
 * React-19-compliant external-store sync: the pre-hydration script owns the
 * html[data-theme] attribute; a MutationObserver keeps this component in
 * lockstep without setState-in-effect (the lint rule exists for a reason —
 * cascading renders). Server snapshot defaults to dark.
 */
function subscribeToTheme(onChange: () => void) {
  const observer = new MutationObserver(onChange);
  observer.observe(document.documentElement, {
    attributes: true,
    attributeFilter: ["data-theme"],
  });
  return () => observer.disconnect();
}

function currentDocumentTheme(): "dark" | "light" {
  return document.documentElement.dataset.theme === "light" ? "light" : "dark";
}

export function ThemeToggle({ className }: ThemeToggleProps) {
  const theme = useSyncExternalStore(subscribeToTheme, currentDocumentTheme, () => "dark");

  const toggle = () => {
    const next = theme === "dark" ? "light" : "dark";
    document.documentElement.dataset.theme = next;
    try {
      window.localStorage.setItem(STORAGE_KEY, next);
    } catch {
      // Private-mode storage failures are cosmetic only.
    }
  };

  return (
    <button
      type="button"
      onClick={toggle}
      aria-label={theme === "dark" ? "Switch to light theme" : "Switch to dark theme"}
      title={theme === "dark" ? "Switch to light theme" : "Switch to dark theme"}
      className={cn(
        "rounded-lg p-2 text-fg-muted transition-colors hover:bg-surface-2 hover:text-fg focus-visible:outline-2 focus-visible:outline-accent",
        className,
      )}
    >
      <Icon name={theme === "dark" ? "sun" : "moon"} size={18} />
    </button>
  );
}

export interface TopbarUser {
  name: string;
  email: string | null;
  roles: readonly string[];
}

export interface TopbarProps {
  user: TopbarUser;
  environment: "sandbox" | "prod";
  /** Auth mode badge tooltip context. */
  authMode: "keycloak" | "mock";
  onOpenSidebar: () => void;
  trailing?: ReactNode;
}

export function Topbar({ user, environment, authMode, onOpenSidebar, trailing }: TopbarProps) {
  const [menuOpen, setMenuOpen] = useState(false);
  const menuRef = useRef<HTMLDivElement>(null);
  const triggerRef = useRef<HTMLButtonElement>(null);

  useEffect(() => {
    if (!menuOpen) return;
    const onClickOutside = (event: MouseEvent) => {
      if (menuRef.current && !menuRef.current.contains(event.target as Node)) {
        setMenuOpen(false);
      }
    };
    const onKeyDown = (event: KeyboardEvent) => {
      if (event.key === "Escape") {
        setMenuOpen(false);
        triggerRef.current?.focus();
      }
    };
    document.addEventListener("mousedown", onClickOutside);
    document.addEventListener("keydown", onKeyDown);
    return () => {
      document.removeEventListener("mousedown", onClickOutside);
      document.removeEventListener("keydown", onKeyDown);
    };
  }, [menuOpen]);

  const initials = user.name
    .split(/\s+/)
    .map((part) => part.charAt(0).toUpperCase())
    .slice(0, 2)
    .join("");

  return (
    <header className="sticky top-0 z-20 flex h-14 items-center gap-3 border-b border-border-subtle bg-surface/95 px-4 backdrop-blur sm:px-6">
      <button
        type="button"
        onClick={onOpenSidebar}
        aria-label="Open navigation"
        className="rounded-lg p-2 text-fg-muted hover:bg-surface-2 hover:text-fg lg:hidden focus-visible:outline-2 focus-visible:outline-accent"
      >
        <Icon name="menu" size={20} />
      </button>

      <div className="flex items-center gap-2">
        <span
          className={cn(
            "rounded-pill px-2.5 py-1 text-[11px] font-semibold tracking-wide uppercase",
            environment === "prod"
              ? "bg-danger-soft text-danger"
              : "bg-accent-soft text-accent",
          )}
          title={
            authMode === "mock"
              ? "Sandbox environment · mock auth (AUTH_MODE=mock)"
              : `Sandbox environment · Keycloak OIDC (${authMode})`
          }
        >
          {environment}
        </span>
        {authMode === "mock" ? (
          <span className="rounded-pill border border-border-strong px-2.5 py-1 text-[11px] font-medium text-fg-muted">
            mock auth
          </span>
        ) : null}
      </div>

      <div className="ml-auto flex items-center gap-2">
        {trailing}
        <ThemeToggle />
        <div className="relative" ref={menuRef}>
          <button
            type="button"
            ref={triggerRef}
            onClick={() => setMenuOpen((open) => !open)}
            aria-haspopup="menu"
            aria-expanded={menuOpen}
            aria-label="User menu"
            className="flex items-center gap-2 rounded-lg px-1.5 py-1.5 hover:bg-surface-2 focus-visible:outline-2 focus-visible:outline-accent"
          >
            <span className="flex h-8 w-8 items-center justify-center rounded-full bg-accent-soft text-xs font-semibold text-accent">
              {initials || "?"}
            </span>
            <span className="hidden text-sm font-medium text-fg sm:inline">{user.name}</span>
            <Icon name="chevron-down" size={14} className="text-fg-subtle" />
          </button>
          {menuOpen ? (
            <div
              role="menu"
              aria-label="User menu"
              className="absolute right-0 mt-2 w-64 rounded-card border border-border-strong bg-surface py-2 shadow-xl"
            >
              <div className="border-b border-border-subtle px-4 pb-2 pt-1">
                <p className="text-sm font-medium text-fg">{user.name}</p>
                {user.email ? (
                  <p className="truncate text-xs text-fg-muted">{user.email}</p>
                ) : null}
              </div>
              {user.roles.length > 0 ? (
                <div className="flex flex-wrap gap-1.5 border-b border-border-subtle px-4 py-2">
                  {user.roles.slice(0, 4).map((role) => (
                    <span
                      key={role}
                      className="rounded-pill bg-surface-2 px-2 py-0.5 font-mono text-[10px] tracking-wide text-fg-muted uppercase"
                    >
                      {role}
                    </span>
                  ))}
                </div>
              ) : null}
              <a
                role="menuitem"
                href="/settings"
                className="flex items-center gap-2.5 px-4 py-2 text-sm text-fg-muted hover:bg-surface-2 hover:text-fg"
              >
                <Icon name="settings" size={16} />
                Settings
              </a>
              <a
                role="menuitem"
                href="/api/auth/logout"
                className="flex items-center gap-2.5 px-4 py-2 text-sm text-danger hover:bg-danger-soft"
              >
                <Icon name="logout" size={16} />
                Sign out
              </a>
            </div>
          ) : null}
        </div>
      </div>
    </header>
  );
}
