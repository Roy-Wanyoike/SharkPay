"use client";

import {
  createContext,
  useCallback,
  useContext,
  useMemo,
  useRef,
  useState,
  type ReactNode,
} from "react";
import { Icon, type IconName } from "@/components/layout/icons";
import { cn } from "@/lib/utils";

export type ToastVariant = "info" | "success" | "warning" | "danger";

export interface ToastInput {
  title: string;
  description?: string;
  variant?: ToastVariant;
  /** Auto-dismiss delay in ms (default 5000). */
  duration?: number;
}

export interface ToastRecord extends Required<Pick<ToastInput, "title">> {
  id: string;
  description?: string;
  variant: ToastVariant;
}

interface ToastContextValue {
  push: (toast: ToastInput) => string;
  dismiss: (id: string) => void;
}

const ToastContext = createContext<ToastContextValue | null>(null);

const VARIANT_META: Record<ToastVariant, { icon: IconName; className: string; role: "status" | "alert" }> = {
  info: { icon: "info", className: "border-info/40", role: "status" },
  success: { icon: "check", className: "border-success/40", role: "status" },
  warning: { icon: "alert", className: "border-warning/40", role: "alert" },
  danger: { icon: "alert", className: "border-danger/40", role: "alert" },
};

const DEFAULT_DURATION = 5000;

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<ToastRecord[]>([]);
  const idCounter = useRef(0);

  const dismiss = useCallback((id: string) => {
    setToasts((current) => current.filter((toast) => toast.id !== id));
  }, []);

  const push = useCallback(
    (input: ToastInput) => {
      idCounter.current += 1;
      const id = `toast-${idCounter.current}`;
      const record: ToastRecord = {
        id,
        title: input.title,
        description: input.description,
        variant: input.variant ?? "info",
      };
      setToasts((current) => [...current, record]);
      if (typeof window !== "undefined") {
        const duration = input.duration ?? DEFAULT_DURATION;
        window.setTimeout(() => dismiss(id), duration);
      }
      return id;
    },
    [dismiss],
  );

  const value = useMemo(() => ({ push, dismiss }), [push, dismiss]);

  return (
    <ToastContext.Provider value={value}>
      {children}
      <div
        aria-label="Notifications"
        role="region"
        className="pointer-events-none fixed right-4 bottom-4 z-[60] flex w-full max-w-sm flex-col gap-2"
      >
        {toasts.map((toast) => {
          const meta = VARIANT_META[toast.variant];
          return (
            <div
              key={toast.id}
              role={meta.role}
              className={cn(
                "animate-toast-in pointer-events-auto flex items-start gap-3 rounded-card border bg-surface px-4 py-3 shadow-xl",
                meta.className,
              )}
            >
              <span
                className={cn(
                  "mt-0.5 text-fg-muted",
                  toast.variant === "success" && "text-success",
                  toast.variant === "warning" && "text-warning",
                  toast.variant === "danger" && "text-danger",
                )}
              >
                <Icon name={meta.icon} size={18} />
              </span>
              <div className="min-w-0 flex-1 space-y-0.5">
                <p className="text-sm font-medium text-fg">{toast.title}</p>
                {toast.description ? (
                  <p className="text-xs text-fg-muted">{toast.description}</p>
                ) : null}
              </div>
              <button
                type="button"
                onClick={() => dismiss(toast.id)}
                aria-label="Dismiss notification"
                className="rounded p-1 text-fg-subtle hover:bg-surface-2 hover:text-fg focus-visible:outline-2 focus-visible:outline-accent"
              >
                <Icon name="close" size={14} />
              </button>
            </div>
          );
        })}
      </div>
    </ToastContext.Provider>
  );
}

export function useToast(): ToastContextValue {
  const context = useContext(ToastContext);
  if (!context) {
    throw new Error("useToast must be used within <ToastProvider>.");
  }
  return context;
}
