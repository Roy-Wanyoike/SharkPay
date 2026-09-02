import { SEED_MARKER } from "@/lib/seed/seed";

/**
 * Page data loader: prefer the live API (typed SDK stubs), fall back to the
 * clearly-marked demo seed while the API is not reachable/wired.
 *
 * Every console page uses this so the app works end-to-end today and
 * switches to real data the moment the API gateway answers — the fallback
 * is surfaced in the UI via a "demo data" badge, never silently.
 */

export type DataSource = "api" | "seed";

export interface Loaded<T> {
  data: T;
  source: DataSource;
}

export async function loadWithFallback<T>(
  label: string,
  apiCall: () => Promise<T>,
  seed: () => T,
): Promise<Loaded<T>> {
  try {
    const data = await apiCall();
    return { data, source: "api" };
  } catch (error) {
    const message = error instanceof Error ? error.message : String(error);
    console.warn(
      `[console] ${label}: API unavailable (${message}) — serving ${SEED_MARKER} data.`,
    );
    return { data: seed(), source: "seed" };
  }
}
