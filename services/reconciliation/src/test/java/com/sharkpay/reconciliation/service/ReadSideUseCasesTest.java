package com.sharkpay.reconciliation.service;

import com.sharkpay.reconciliation.domain.AgingBucket;
import com.sharkpay.reconciliation.domain.BreakState;
import com.sharkpay.reconciliation.domain.ReconRunState;
import com.sharkpay.reconciliation.domain.SettlementReport;
import com.sharkpay.reconciliation.testsupport.ReconTestEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Read side of the recon console: break detail with LIVE aging (recomputed
 * from detection time — the console can never disagree with the sweeper),
 * composable break filters, run detail with its breaks, settlement reports
 * by run / by exact window / per provider.
 */
class ReadSideUseCasesTest {

    private static final String OLD_RUN_KEY = "key-old";
    private static final String NEW_RUN_KEY = "key-new";
    private static final java.time.Instant SECOND_WINDOW_FROM =
            java.time.Instant.parse("2026-09-02T00:00:00Z");
    private static final java.time.Instant SECOND_WINDOW_TO =
            java.time.Instant.parse("2026-09-03T00:00:00Z");

    private ReconTestEnv env;
    private String oldBreakId;

    @BeforeEach
    void setUp() {
        env = new ReconTestEnv();
        // run 1 over the default window: one break detected at START
        env.seedProviderLine("hc_old", "CONFIRMED", 1_000, 0);
        oldBreakId = env.triggerDefault(OLD_RUN_KEY).breaks().get(0).id();

        // run 2 six hours later over the NEXT day's window: two fresh breaks
        env.clock.advance(Duration.ofHours(6));
        env.providers.seed("hc_new_ghost", "CONFIRMED", 2_000, "KES", 0,
                java.time.Instant.parse("2026-09-02T12:00:00Z"));
        env.providers.seed("hc_new_fee", "CONFIRMED", 3_000, "KES", 650,
                java.time.Instant.parse("2026-09-02T12:00:00Z"));
        env.ledgerStatement.seed("int_new_fee", "hc_new_fee", "CONFIRMED", 3_000, "KES", 500,
                java.time.Instant.parse("2026-09-02T12:00:00Z"));
        env.triggerRun.trigger(NEW_RUN_KEY, "honeycoin", SECOND_WINDOW_FROM, SECOND_WINDOW_TO);
    }

    @Test
    void aBreakViewCarriesLiveAgingNotThePersistedBucket() {
        // 6 h after detection both breaks are FRESH
        assertThat(env.getBreak.get(oldBreakId).bucket()).isEqualTo(AgingBucket.FRESH);
        assertThat(env.getBreak.get(oldBreakId).ageHours()).isEqualTo(6);

        // the clock crosses 24 h — the READ side recomputes without any sweep
        env.clock.advance(Duration.ofHours(19));
        BreakView aged = env.getBreak.get(oldBreakId);
        assertThat(aged.bucket()).isEqualTo(AgingBucket.AGING);
        assertThat(aged.ageHours()).isEqualTo(25);

        // the persisted bucket is still FRESH until the sweeper runs — the
        // console shows the live truth either way
        assertThat(env.breaks.findById(oldBreakId).orElseThrow().bucket())
                .isEqualTo(AgingBucket.FRESH);
    }

    @Test
    void anUnknownBreakIsA404() {
        assertThatThrownBy(() -> env.getBreak.get("brk_unknown"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("brk_unknown");
    }

    @Test
    void breakListsFilterByStateAgingAndProviderComposably() {
        // no filter: all three breaks (2 active + 1 active) — active first
        assertThat(env.listBreaks.list(null, null, null)).hasSize(3);

        // state filter (OPEN → INVESTIGATING → RESOLVED: the legal manual path)
        env.transitionBreak.transition(oldBreakId, "investigating", "ops.alice",
                "hypothesis");
        env.transitionBreak.transition(oldBreakId, "resolved", "ops.alice", "matched");
        assertThat(env.listBreaks.list("resolved", null, null)).hasSize(1);
        assertThat(env.listBreaks.list("open", null, null)).hasSize(2);
        // unknown filter values are rejected loudly
        assertThatThrownBy(() -> env.listBreaks.list("closed", null, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> env.listBreaks.list(null, "ancient", null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown aging bucket");

        // live-aging filter: cross 24 h for the old break only
        env.clock.advance(Duration.ofHours(19));
        assertThat(env.listBreaks.list(null, "aging", null)).hasSize(1);
        assertThat(env.listBreaks.list(null, "fresh", null)).hasSize(2);

        // provider filter (another provider's breaks never leak in)
        assertThat(env.listBreaks.list(null, null, "honeycoin")).hasSize(3);
        assertThat(env.listBreaks.list(null, null, "other")).isEmpty();
        // composable: aging + provider
        assertThat(env.listBreaks.list(null, "aging", "honeycoin")).hasSize(1);
        assertThat(env.listBreaks.list(null, "aging", "other")).isEmpty();
    }

    @Test
    void unfilteredListsShowActiveBreaksBeforeTheTerminalHistory() {
        env.transitionBreak.transition(oldBreakId, "investigating", "ops.alice",
                "hypothesis");
        env.transitionBreak.transition(oldBreakId, "resolved", "ops.alice", "matched");
        java.util.List<BreakView> all = env.listBreaks.list(null, null, null);
        assertThat(all).hasSize(3);
        assertThat(all.subList(0, 2))
                .allSatisfy(view -> assertThat(view.break_().state().isActive()).isTrue());
        assertThat(all.get(2).break_().state()).isEqualTo(BreakState.RESOLVED);
    }

    @Test
    void aRunViewCarriesTheRunAndItsBreaksWithLiveAging() {
        var result = env.getRun.get(env.runs.listByProvider("honeycoin").get(0).id());
        assertThat(result.run().state()).isEqualTo(ReconRunState.COMPLETED);
        assertThat(result.breaks()).hasSize(2);
        assertThat(result.breaks())
                .allSatisfy(view -> assertThat(view.bucket()).isEqualTo(AgingBucket.FRESH));

        assertThatThrownBy(() -> env.getRun.get("run_unknown"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("run_unknown");
    }

    @Test
    void settlementReportsAreAddressableByRunWindowAndProvider() {
        // run 1 is the OLDER run (list is newest-first: index 1)
        SettlementReport oldReport = env.settlementReports.byRun(
                env.runs.listByProvider("honeycoin").get(1).id());
        assertThat(oldReport.currencyLines()).hasSize(1);
        assertThat(oldReport.breakSummary().total()).isEqualTo(1);

        // by exact window: the second day's window has exactly one covering run
        SettlementReport byWindow = env.settlementReports.byProviderAndWindow("honeycoin",
                SECOND_WINDOW_FROM, SECOND_WINDOW_TO);
        assertThat(byWindow.breakSummary().total()).isEqualTo(2);

        // provider list, newest first
        java.util.List<SettlementReport> reports = env.settlementReports.listByProvider(
                "honeycoin");
        assertThat(reports).hasSize(2);
        assertThat(reports.get(0).generatedAt()).isAfter(reports.get(1).generatedAt());

        // unknown window / blank provider
        assertThatThrownBy(() -> env.settlementReports.byProviderAndWindow("honeycoin",
                java.time.Instant.parse("2026-09-05T00:00:00Z"),
                java.time.Instant.parse("2026-09-06T00:00:00Z")))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("no settlement report");
        assertThatThrownBy(() -> env.settlementReports.byProviderAndWindow(" ", ReconTestEnv.WINDOW_FROM,
                ReconTestEnv.WINDOW_TO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("provider must not be blank");
        assertThatThrownBy(() -> env.settlementReports.listByProvider(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void publicIdsAreWirePatterned() {
        assertThat(Ids.requestId()).matches("^req_[0-9a-f]{32}$");
        assertThat(Ids.requestId()).isNotEqualTo(Ids.requestId());
    }

    @Test
    void aNegativeAgedBreakReadsAsZeroHours() {
        // detected in the future relative to the clock: read clamps at 0
        env.clock.set(ReconTestEnv.START.minusSeconds(60));
        BreakView view = env.getBreak.get(oldBreakId);
        assertThat(view.ageHours()).isZero();
        assertThat(view.bucket()).isEqualTo(AgingBucket.FRESH);
    }
}
