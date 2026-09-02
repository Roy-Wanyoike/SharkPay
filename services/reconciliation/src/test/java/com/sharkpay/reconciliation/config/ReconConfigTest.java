package com.sharkpay.reconciliation.config;

import com.sharkpay.reconciliation.fakes.InMemoryCompensationEntryRepository;
import com.sharkpay.reconciliation.fakes.InMemoryIdempotencyStore;
import com.sharkpay.reconciliation.fakes.InMemoryReconBreakRepository;
import com.sharkpay.reconciliation.fakes.InMemoryReconRunRepository;
import com.sharkpay.reconciliation.fakes.InMemorySettlementReportRepository;
import com.sharkpay.reconciliation.ports.CompensationEntryRepository;
import com.sharkpay.reconciliation.ports.EventPublisher;
import com.sharkpay.reconciliation.ports.IdempotencyStore;
import com.sharkpay.reconciliation.ports.LedgerPort;
import com.sharkpay.reconciliation.ports.LedgerStatementPort;
import com.sharkpay.reconciliation.ports.ProviderStatementPort;
import com.sharkpay.reconciliation.ports.Randomness;
import com.sharkpay.reconciliation.ports.ReconBreakRepository;
import com.sharkpay.reconciliation.ports.ReconRunRepository;
import com.sharkpay.reconciliation.ports.SettlementReportRepository;
import com.sharkpay.reconciliation.service.ApproveAndExecuteCompensationUseCase;
import com.sharkpay.reconciliation.service.GetBreakUseCase;
import com.sharkpay.reconciliation.service.GetReconRunUseCase;
import com.sharkpay.reconciliation.service.GetSettlementReportUseCase;
import com.sharkpay.reconciliation.service.ListBreaksUseCase;
import com.sharkpay.reconciliation.service.ProposeCompensationUseCase;
import com.sharkpay.reconciliation.service.SweepAgingBreaksUseCase;
import com.sharkpay.reconciliation.service.TransitionBreakUseCase;
import com.sharkpay.reconciliation.service.TriggerReconRunUseCase;
import com.sharkpay.reconciliation.testsupport.ReconTestEnv;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the production {@link ReconConfig} bean factories without a
 * Spring context: every factory builds a usable object, the cross-service
 * port placeholders fail fast and loud (money-path honesty, ADR 003 §3),
 * and the storage-backed port beans are satisfied by component-scanned JPA
 * adapters at runtime (covered by JpaAdaptersTest). Use-case behavior is
 * proven on the test-tree fakes, which mirror those adapters.
 */
class ReconConfigTest {

    private final ReconTestEnv env = new ReconTestEnv();
    private final ReconConfig config = new ReconConfig();

    @Test
    void clockBeanReturnsUtcNow() {
        Clock clock = config.clock();
        Instant before = Instant.now().minusSeconds(5);
        Instant after = Instant.now().plusSeconds(5);
        assertThat(clock.instant()).isBetween(before, after);
    }

    @Test
    void randomnessAndEventFactoryBeansAreWired() {
        Randomness randomness = config.randomness();
        assertThat(randomness.runId()).startsWith("run_");
        assertThat(config.reconEvents(randomness)).isNotNull();
    }

    @Test
    void eventPublisherBeanIsTheLoggingPlaceholder() {
        EventPublisher publisher = config.eventPublisher();
        assertThat(publisher).isInstanceOf(LoggingEventPublisher.class);
    }

    @Test
    void providerStatementPlaceholderFailsFastAndLoud() {
        ProviderStatementPort providers = config.providerStatementPort();
        assertThat(providers).isInstanceOf(IntegrationPendingProviderStatement.class);
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = Instant.parse("2026-09-02T00:00:00Z");
        assertThatThrownBy(() -> providers.fetch("honeycoin", from, to))
                .isInstanceOf(com.sharkpay.reconciliation.domain.StatementUnavailableException.class)
                .hasMessageContaining("provider statement unavailable for provider honeycoin")
                .hasMessageContaining("integration pending")
                .hasMessageContaining("/v1/providers/honeycoin/reconcile")
                .hasMessageContaining("from=" + from)
                .hasMessageContaining("to=" + to);
    }

    @Test
    void ledgerStatementPlaceholderFailsFastAndLoud() {
        LedgerStatementPort ledger = config.ledgerStatementPort();
        assertThat(ledger).isInstanceOf(IntegrationPendingLedgerStatement.class);
        Instant from = Instant.parse("2026-09-01T00:00:00Z");
        Instant to = Instant.parse("2026-09-02T00:00:00Z");
        assertThatThrownBy(() -> ledger.internalLines("honeycoin", from, to))
                .isInstanceOf(com.sharkpay.reconciliation.domain.StatementUnavailableException.class)
                .hasMessageContaining("ledger statement unavailable for provider honeycoin")
                .hasMessageContaining("integration pending")
                .hasMessageContaining("/internal/accounts/{id}/statement")
                .hasMessageContaining("window=[" + from + ", " + to + ")");
    }

    @Test
    void ledgerPostingPlaceholderFailsFastAndLoud() {
        LedgerPort ledger = config.ledgerPort();
        assertThat(ledger).isInstanceOf(IntegrationPendingLedgerPort.class);
        LedgerPort.LedgerPosting posting = LedgerPort.LedgerPosting.of("ops:adj:brk_x",
                LedgerPort.Source.OPS, java.util.UUID.randomUUID(),
                LedgerPort.EntryType.ADJUSTMENT, "r", portLegs());
        assertThatThrownBy(() -> ledger.post(posting))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("integration pending")
                .hasMessageContaining("/internal/transactions")
                .hasMessageContaining("ops:adj:brk_x");
    }

    @Test
    void allUseCaseBeanMethodsBuildWorkingObjects() {
        // the storage-backed ports, satisfied here by their in-tree mirrors
        ReconRunRepository runs = env.runs;
        ReconBreakRepository breaks = env.breaks;
        SettlementReportRepository reports = env.reports;
        CompensationEntryRepository compensations = env.compensations;
        IdempotencyStore idempotency = env.idempotency;
        EventPublisher events = env.events;
        Randomness randomness = env.randomness;
        Clock clock = env.clock;

        TriggerReconRunUseCase triggerRun = config.triggerReconRunUseCase(env.providers,
                env.ledgerStatement, runs, breaks, reports, idempotency, events,
                config.reconEvents(randomness), randomness, clock);
        GetReconRunUseCase getRun = config.getReconRunUseCase(runs, breaks, clock);
        GetBreakUseCase getBreak = config.getBreakUseCase(breaks, clock);
        ListBreaksUseCase listBreaks = config.listBreaksUseCase(breaks, clock);
        TransitionBreakUseCase transitionBreak = config.transitionBreakUseCase(breaks, clock);
        ProposeCompensationUseCase propose = config.proposeCompensationUseCase(compensations,
                breaks, idempotency, randomness, clock);
        ApproveAndExecuteCompensationUseCase approve =
                config.approveAndExecuteCompensationUseCase(compensations, breaks, env.ledger,
                        events, config.reconEvents(randomness), randomness, clock);
        SweepAgingBreaksUseCase sweep = config.sweepAgingBreaksUseCase(breaks, events,
                config.reconEvents(randomness), clock);
        GetSettlementReportUseCase settlementReports = config.getSettlementReportUseCase(reports);

        // smoke: the whole wiring works end to end on the fake ports (the
        // same money-safety path as the service tests: detect → investigate
        // → 4-eyes compensate, with the run completing zero-break first)
        env.seedMatch("hc_clean", 150_000, "KES", 500);
        assertThat(triggerRun.trigger("key-1", "honeycoin", ReconTestEnv.WINDOW_FROM,
                ReconTestEnv.WINDOW_TO).run().breakCount()).isZero();
        env.seedProviderLine("hc_ghost", "CONFIRMED", 2_000, 0);
        TriggerReconRunUseCase.Result second = triggerRun.trigger("key-2", "honeycoin",
                ReconTestEnv.WINDOW_FROM, ReconTestEnv.WINDOW_TO);
        String breakId = second.breaks().get(0).id();
        String runId = second.run().id();
        transitionBreak.transition(breakId, "investigating", "ops.alice", "hypothesis");
        assertThat(getBreak.get(breakId).break_().state().toString()).isEqualTo("INVESTIGATING");
        assertThat(listBreaks.list("investigating", null, null)).hasSize(1);
        String compensationId = propose.propose("key-prop", breakId, "ops.alice", "variance",
                domainLegs(), null).entry().id();
        ApproveAndExecuteCompensationUseCase.Result executed =
                approve.approveAndExecute(compensationId, "ops.bob");
        assertThat(executed.break_().compensationId()).isEqualTo(compensationId);
        assertThat(env.ledger.committedCount()).isEqualTo(1);
        env.clock.advance(java.time.Duration.ofHours(25));
        assertThat(sweep.sweep().escalatedCount()).isZero();  // compensated = not swept
        assertThat(getRun.get(runId).run().state().toString()).isEqualTo("COMPLETED");
        assertThat(getRun.get(runId).breaks()).hasSize(1);
        assertThat(settlementReports.byProviderAndWindow("honeycoin", ReconTestEnv.WINDOW_FROM,
                ReconTestEnv.WINDOW_TO)).isNotNull();
    }

    @Test
    void configSmokeReflectsTheFakePortsAsTheJpaAdaptersWould() {
        // the config never references the fakes: its port parameters are the
        // port interfaces themselves (satisfied by JPA adapters at runtime).
        // Wiring fresh fake instances through the same factories proves the
        // factory signatures accept any port implementation.
        ReconRunRepository runs = new InMemoryReconRunRepository();
        ReconBreakRepository breaks = new InMemoryReconBreakRepository();
        SettlementReportRepository reports = new InMemorySettlementReportRepository();
        CompensationEntryRepository compensations = new InMemoryCompensationEntryRepository();
        IdempotencyStore idempotency = new InMemoryIdempotencyStore();
        TriggerReconRunUseCase triggerRun = config.triggerReconRunUseCase(env.providers,
                env.ledgerStatement, runs, breaks, reports, idempotency, env.events,
                config.reconEvents(env.randomness), env.randomness, env.clock);
        env.seedMatch("hc_clean", 1_000, "KES", 0);
        assertThat(triggerRun.trigger("cfg-1", "honeycoin", ReconTestEnv.WINDOW_FROM,
                ReconTestEnv.WINDOW_TO).run().state().toString()).isEqualTo("COMPLETED");
    }

    private static java.util.List<LedgerPort.Leg> portLegs() {
        return java.util.List.of(
                new LedgerPort.Leg("suspense:recon:KES", LedgerPort.Direction.DEBIT,
                        com.sharkpay.money.Money.of(500, "KES")),
                new LedgerPort.Leg("honeycoin:settlement:KES", LedgerPort.Direction.CREDIT,
                        com.sharkpay.money.Money.of(500, "KES")));
    }

    /** The drafted legs operator A proposes (domain type). */
    private static java.util.List<com.sharkpay.reconciliation.domain.CompensationLeg> domainLegs() {
        return java.util.List.of(
                new com.sharkpay.reconciliation.domain.CompensationLeg("suspense:recon:KES",
                        com.sharkpay.reconciliation.domain.PostingDirection.DEBIT,
                        com.sharkpay.money.Money.of(500, "KES")),
                new com.sharkpay.reconciliation.domain.CompensationLeg("honeycoin:settlement:KES",
                        com.sharkpay.reconciliation.domain.PostingDirection.CREDIT,
                        com.sharkpay.money.Money.of(500, "KES")));
    }
}
