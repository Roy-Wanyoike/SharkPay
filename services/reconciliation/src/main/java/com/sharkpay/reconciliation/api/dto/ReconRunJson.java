package com.sharkpay.reconciliation.api.dto;

import com.sharkpay.reconciliation.domain.ReconRun;
import com.sharkpay.reconciliation.domain.ReconRunState;
import com.sharkpay.reconciliation.service.BreakView;

import java.time.Instant;
import java.util.List;

/**
 * A recon run on the wire, with the run's breaks when the response is the
 * run detail (create or get-by-id); the breaks array is omitted (NON_NULL)
 * on list responses.
 */
public record ReconRunJson(String id, String provider, String state, Instant window_from,
                           Instant window_to, Instant started_at, Instant completed_at,
                           String failure_reason, int provider_lines, int internal_lines,
                           int matched_lines, int break_count, List<ReconBreakJson> breaks) {

    public static ReconRunJson of(ReconRun run, List<BreakView> breakViews) {
        List<ReconBreakJson> breaks = breakViews == null ? null
                : breakViews.stream().map(ReconBreakJson::of).toList();
        return new ReconRunJson(run.id(), run.provider(), run.state().wireName(),
                run.window().from(), run.window().to(), run.startedAt(), run.completedAt(),
                run.failureReason(), run.providerLines(), run.internalLines(), run.matchedPairs(),
                run.breakCount(), breaks);
    }

    public static ReconRunJson summary(ReconRun run) {
        return of(run, null);
    }

    /** Only COMPLETED runs carry counts; FAILED runs stay zeroed. */
    public static boolean stateCarriesCounts(ReconRunState state) {
        return state == ReconRunState.COMPLETED;
    }
}
