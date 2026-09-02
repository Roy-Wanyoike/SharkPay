"use client";

import { useState, type ReactNode } from "react";
import { Tabs, TabPanel } from "@/components/ui/Tabs";

export interface WebhooksTabsSectionProps {
  endpoints: ReactNode;
  deliveries: ReactNode;
  counts: { endpoints: number; deliveries: number };
}

/** Client island wrapping the endpoints/deliveries tables in accessible Tabs. */
export function WebhooksTabsSection({
  endpoints,
  deliveries,
  counts,
}: WebhooksTabsSectionProps) {
  const [activeId, setActiveId] = useState("endpoints");
  return (
    <div>
      <Tabs
        idPrefix="webhooks"
        tabs={[
          { id: "endpoints", label: "Endpoints", count: counts.endpoints },
          { id: "deliveries", label: "Recent deliveries", count: counts.deliveries },
        ]}
        activeId={activeId}
        onChange={setActiveId}
      />
      <TabPanel
        idPrefix="webhooks"
        tabId="endpoints"
        className={activeId === "endpoints" ? "" : "hidden"}
      >
        {endpoints}
      </TabPanel>
      <TabPanel
        idPrefix="webhooks"
        tabId="deliveries"
        className={activeId === "deliveries" ? "" : "hidden"}
      >
        {deliveries}
      </TabPanel>
    </div>
  );
}
