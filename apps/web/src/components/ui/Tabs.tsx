"use client";

import { useId, useRef, type ReactNode } from "react";
import { cn } from "@/lib/utils";

export interface TabItem {
  id: string;
  label: string;
  /** Optional trailing count badge. */
  count?: number;
}

export interface TabsProps {
  tabs: readonly TabItem[];
  activeId: string;
  onChange: (id: string) => void;
  /** Wired to the aria-controls/aria-labelledby pairs. */
  idPrefix?: string;
  className?: string;
}

const SUPPORTED_KEYS = new Set(["ArrowLeft", "ArrowRight", "Home", "End"]);

/**
 * Accessible tab list (WAI-ARIA authoring pattern): roving tabindex,
 * ArrowLeft/ArrowRight/Home/End keyboard navigation, aria-selected and
 * aria-controls wiring. Panels are rendered by the consumer with
 * <TabPanel> below.
 */
export function Tabs({ tabs, activeId, onChange, idPrefix, className }: TabsProps) {
  const baseId = useId();
  const prefix = idPrefix ?? baseId;
  const refs = useRef<Array<HTMLButtonElement | null>>([]);

  const focusTab = (index: number) => {
    const target = refs.current[index];
    target?.focus();
  };

  const onKeyDown = (event: React.KeyboardEvent<HTMLButtonElement>) => {
    if (!SUPPORTED_KEYS.has(event.key)) return;
    event.preventDefault();
    const currentIndex = tabs.findIndex((tab) => tab.id === activeId);
    let nextIndex = currentIndex;
    if (event.key === "ArrowRight") {
      nextIndex = (currentIndex + 1) % tabs.length;
    } else if (event.key === "ArrowLeft") {
      nextIndex = (currentIndex - 1 + tabs.length) % tabs.length;
    } else if (event.key === "Home") {
      nextIndex = 0;
    } else if (event.key === "End") {
      nextIndex = tabs.length - 1;
    }
    const nextTab = tabs[nextIndex];
    if (nextTab && nextTab.id !== activeId) {
      onChange(nextTab.id);
    }
    if (nextIndex >= 0 && nextIndex < tabs.length) {
      focusTab(nextIndex);
    }
  };

  return (
    <div
      role="tablist"
      aria-orientation="horizontal"
      className={cn("flex gap-1 overflow-x-auto border-b border-border-subtle", className)}
    >
      {tabs.map((tab, index) => {
        const selected = tab.id === activeId;
        return (
          <button
            key={tab.id}
            ref={(element) => {
              refs.current[index] = element;
            }}
            type="button"
            role="tab"
            id={`${prefix}-tab-${tab.id}`}
            aria-selected={selected}
            aria-controls={`${prefix}-panel-${tab.id}`}
            tabIndex={selected ? 0 : -1}
            onClick={() => onChange(tab.id)}
            onKeyDown={onKeyDown}
            className={cn(
              "-mb-px border-b-2 px-4 py-2.5 text-sm font-medium whitespace-nowrap transition-colors",
              "focus-visible:outline-2 focus-visible:outline-offset-[-2px] focus-visible:outline-accent",
              selected
                ? "border-accent text-fg"
                : "border-transparent text-fg-muted hover:border-border-strong hover:text-fg",
            )}
          >
            {tab.label}
            {typeof tab.count === "number" ? (
              <span
                className={cn(
                  "ml-2 rounded-pill px-1.5 py-0.5 text-xs tabular-nums",
                  selected ? "bg-accent-soft text-accent" : "bg-surface-2 text-fg-subtle",
                )}
              >
                {tab.count}
              </span>
            ) : null}
          </button>
        );
      })}
    </div>
  );
}

export interface TabPanelProps {
  /** Must match the tab id the panel belongs to. */
  tabId: string;
  idPrefix?: string;
  children: ReactNode;
  className?: string;
}

export function TabPanel({ tabId, idPrefix, children, className }: TabPanelProps) {
  const baseId = useId();
  const prefix = idPrefix ?? baseId;
  return (
    <div
      role="tabpanel"
      id={`${prefix}-panel-${tabId}`}
      aria-labelledby={`${prefix}-tab-${tabId}`}
      tabIndex={0}
      className={cn("pt-4 focus-visible:outline-2 focus-visible:outline-accent", className)}
    >
      {children}
    </div>
  );
}
