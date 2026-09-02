"use client";

import { useState, type ReactNode } from "react";
import { Sidebar } from "@/components/layout/Sidebar";
import { Topbar, type TopbarUser } from "@/components/layout/Topbar";

export interface AppShellProps {
  user: TopbarUser;
  environment: "sandbox" | "prod";
  authMode: "keycloak" | "mock";
  children: ReactNode;
}

/**
 * Console app shell: persistent sidebar (`lg:`+), off-canvas drawer below,
 * sticky topbar with the environment badge and user menu. The children are
 * server-rendered page content passed through this client boundary.
 */
export function AppShell({ user, environment, authMode, children }: AppShellProps) {
  const [sidebarOpen, setSidebarOpen] = useState(false);

  return (
    <div className="min-h-dvh bg-background">
      <Sidebar open={sidebarOpen} onClose={() => setSidebarOpen(false)} />
      <div className="flex min-h-dvh flex-col lg:pl-64">
        <Topbar
          user={user}
          environment={environment}
          authMode={authMode}
          onOpenSidebar={() => setSidebarOpen(true)}
        />
        <main className="flex-1">{children}</main>
      </div>
    </div>
  );
}
