"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { Icon, SharkpayMark } from "@/components/layout/icons";
import { NAV_SECTIONS, isNavItemActive } from "@/lib/nav";
import { cn } from "@/lib/utils";

export interface SidebarProps {
  /** Mobile off-canvas open state (controlled by AppShell). */
  open: boolean;
  onClose: () => void;
}

/**
 * Console navigation: sections from src/lib/nav.ts, active-route
 * highlighting via usePathname, `lg:`-up persistent, below that an
 * off-canvas drawer with an overlay. Current page is communicated with
 * aria-current="page".
 */
export function Sidebar({ open, onClose }: SidebarProps) {
  const pathname = usePathname();

  return (
    <>
      {open ? (
        <button
          type="button"
          aria-label="Close navigation"
          onClick={onClose}
          className="fixed inset-0 z-30 bg-black/50 lg:hidden"
        />
      ) : null}
      <nav
        aria-label="Primary"
        className={cn(
          "fixed inset-y-0 left-0 z-40 flex w-64 flex-col border-r border-border-subtle bg-surface transition-transform duration-200 lg:translate-x-0",
          open ? "translate-x-0" : "-translate-x-full",
        )}
      >
        <div className="flex h-14 items-center gap-2.5 border-b border-border-subtle px-4">
          <Link
            href="/"
            className="flex items-center gap-2.5 rounded-lg px-1 py-1 focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent"
          >
            <span className="text-accent">
              <SharkpayMark size={26} />
            </span>
            <span className="text-sm font-semibold tracking-wide text-fg">
              SharkPay <span className="text-fg-subtle">Console</span>
            </span>
          </Link>
          <button
            type="button"
            onClick={onClose}
            aria-label="Close navigation"
            className="ml-auto rounded-lg p-1.5 text-fg-subtle hover:bg-surface-2 hover:text-fg lg:hidden focus-visible:outline-2 focus-visible:outline-accent"
          >
            <Icon name="close" size={18} />
          </button>
        </div>

        <div className="flex-1 space-y-6 overflow-y-auto px-3 py-4">
          {NAV_SECTIONS.map((section, sectionIndex) => (
            <div key={section.title ?? `section-${sectionIndex}`} className="space-y-1">
              {section.title ? (
                <p className="px-3 pb-1 text-[11px] font-semibold tracking-widest text-fg-subtle uppercase">
                  {section.title}
                </p>
              ) : null}
              {section.items.map((item) => {
                const active = isNavItemActive(pathname, item.href);
                return (
                  <Link
                    key={item.href}
                    href={item.href}
                    aria-current={active ? "page" : undefined}
                    title={item.description}
                    onClick={onClose}
                    className={cn(
                      "flex items-center gap-3 rounded-lg px-3 py-2 text-sm font-medium transition-colors",
                      "focus-visible:outline-2 focus-visible:outline-offset-2 focus-visible:outline-accent",
                      active
                        ? "bg-accent-soft text-accent"
                        : "text-fg-muted hover:bg-surface-2 hover:text-fg",
                    )}
                  >
                    <Icon name={item.icon} size={18} />
                    {item.label}
                  </Link>
                );
              })}
            </div>
          ))}
        </div>

        <div className="border-t border-border-subtle px-4 py-3">
          <p className="text-[11px] text-fg-subtle">
            Operations console · v0.1 foundation
          </p>
        </div>
      </nav>
    </>
  );
}
