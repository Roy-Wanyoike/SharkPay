import { Badge } from "@/components/ui/Badge";
import type { DataSource } from "@/lib/data/load";

/**
 * Marks whether a page is rendering live API data or the demo seed. Always
 * visible in the UI — the fallback is never silent.
 */
export function DataOrigin({ source }: { source: DataSource }) {
  if (source === "api") {
    return (
      <Badge variant="success" dot title="Data served by the live SharkPay API">
        live API
      </Badge>
    );
  }
  return (
    <Badge
      variant="warning"
      dot
      title="Live API unavailable — showing clearly-marked demo seed data"
    >
      demo data
    </Badge>
  );
}
