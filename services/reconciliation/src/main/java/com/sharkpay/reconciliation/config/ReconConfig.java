package com.sharkpay.reconciliation.config;

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
import com.sharkpay.reconciliation.events.ReconEvents;
import com.sharkpay.reconciliation.service.ApproveAndExecuteCompensationUseCase;
import com.sharkpay.reconciliation.service.GetBreakUseCase;
import com.sharkpay.reconciliation.service.GetReconRunUseCase;
import com.sharkpay.reconciliation.service.GetSettlementReportUseCase;
import com.sharkpay.reconciliation.service.ListBreaksUseCase;
import com.sharkpay.reconciliation.service.ProposeCompensationUseCase;
import com.sharkpay.reconciliation.service.SweepAgingBreaksUseCase;
import com.sharkpay.reconciliation.service.TransitionBreakUseCase;
import com.sharkpay.reconciliation.service.TriggerReconRunUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Production wiring of the hexagon, mirroring the wallet service's
 * {@code WalletConfig}: use-case beans depend only on ports.
 *
 * <p>Port adapters:</p>
 * <ul>
 *   <li>storage-backed ports ({@link ReconRunRepository},
 *       {@link ReconBreakRepository}, {@link SettlementReportRepository},
 *       {@link CompensationEntryRepository}, {@link IdempotencyStore}) —
 *       the JPA adapters in the storage package (@Repository,
 *       component-scanned, Spring Data repositories against the
 *       Flyway-managed schema);</li>
 *   <li>{@link EventPublisher} — {@link LoggingEventPublisher} (structured
 *       logging) until the NATS/Kafka CloudEvent adapter lands;</li>
 *   <li>cross-service ports ({@link ProviderStatementPort},
 *       {@link LedgerStatementPort}, {@link LedgerPort}) — fail-fast
 *       integration-pending placeholders until the REST adapters
 *       (providers-gateway reconcile report, Go ledger statement + posting
 *       API) are wired by the integrator (ADR 003 §3).</li>
 * </ul>
 *
 * <p>Local tests never boot this context: they assemble the same use-cases
 * on the in-tree fakes ({@code com.sharkpay.reconciliation.fakes} in
 * src/test).</p>
 */
@Configuration(proxyBeanMethods = false)
public class ReconConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public Randomness randomness() {
        return new StandardRandomness();
    }

    @Bean
    public ReconEvents reconEvents(Randomness randomness) {
        return new ReconEvents(randomness);
    }

    @Bean
    public EventPublisher eventPublisher() {
        return new LoggingEventPublisher();
    }

    @Bean
    public ProviderStatementPort providerStatementPort() {
        return new IntegrationPendingProviderStatement();
    }

    @Bean
    public LedgerStatementPort ledgerStatementPort() {
        return new IntegrationPendingLedgerStatement();
    }

    @Bean
    public LedgerPort ledgerPort() {
        return new IntegrationPendingLedgerPort();
    }

    @Bean
    public TriggerReconRunUseCase triggerReconRunUseCase(ProviderStatementPort providers,
                                                         LedgerStatementPort ledger,
                                                         ReconRunRepository runs,
                                                         ReconBreakRepository breaks,
                                                         SettlementReportRepository reports,
                                                         IdempotencyStore idempotency,
                                                         EventPublisher events, ReconEvents eventFactory,
                                                         Randomness randomness, Clock clock) {
        return new TriggerReconRunUseCase(providers, ledger, runs, breaks, reports, idempotency,
                events, eventFactory, randomness, clock);
    }

    @Bean
    public GetReconRunUseCase getReconRunUseCase(ReconRunRepository runs,
                                                 ReconBreakRepository breaks, Clock clock) {
        return new GetReconRunUseCase(runs, breaks, clock);
    }

    @Bean
    public GetBreakUseCase getBreakUseCase(ReconBreakRepository breaks, Clock clock) {
        return new GetBreakUseCase(breaks, clock);
    }

    @Bean
    public ListBreaksUseCase listBreaksUseCase(ReconBreakRepository breaks, Clock clock) {
        return new ListBreaksUseCase(breaks, clock);
    }

    @Bean
    public TransitionBreakUseCase transitionBreakUseCase(ReconBreakRepository breaks, Clock clock) {
        return new TransitionBreakUseCase(breaks, clock);
    }

    @Bean
    public ProposeCompensationUseCase proposeCompensationUseCase(
            CompensationEntryRepository compensations, ReconBreakRepository breaks,
            IdempotencyStore idempotency, Randomness randomness, Clock clock) {
        return new ProposeCompensationUseCase(compensations, breaks, idempotency, randomness, clock);
    }

    @Bean
    public ApproveAndExecuteCompensationUseCase approveAndExecuteCompensationUseCase(
            CompensationEntryRepository compensations, ReconBreakRepository breaks, LedgerPort ledger,
            EventPublisher events, ReconEvents eventFactory, Randomness randomness, Clock clock) {
        return new ApproveAndExecuteCompensationUseCase(compensations, breaks, ledger, events,
                eventFactory, randomness, clock);
    }

    @Bean
    public SweepAgingBreaksUseCase sweepAgingBreaksUseCase(ReconBreakRepository breaks,
                                                            EventPublisher events,
                                                            ReconEvents eventFactory, Clock clock) {
        return new SweepAgingBreaksUseCase(breaks, events, eventFactory, clock);
    }

    @Bean
    public GetSettlementReportUseCase getSettlementReportUseCase(
            SettlementReportRepository reports) {
        return new GetSettlementReportUseCase(reports);
    }
}
