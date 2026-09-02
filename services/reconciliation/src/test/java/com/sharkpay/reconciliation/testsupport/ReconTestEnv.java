package com.sharkpay.reconciliation.testsupport;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sharkpay.reconciliation.api.CompensationsController;
import com.sharkpay.reconciliation.api.GlobalExceptionHandler;
import com.sharkpay.reconciliation.api.ReconBreaksController;
import com.sharkpay.reconciliation.api.ReconRunsController;
import com.sharkpay.reconciliation.api.SettlementReportsController;
import com.sharkpay.reconciliation.events.ReconEvents;
import com.sharkpay.reconciliation.fakes.FakeLedgerPort;
import com.sharkpay.reconciliation.fakes.FakeLedgerStatement;
import com.sharkpay.reconciliation.fakes.FakeProviderStatement;
import com.sharkpay.reconciliation.fakes.InMemoryCompensationEntryRepository;
import com.sharkpay.reconciliation.fakes.InMemoryIdempotencyStore;
import com.sharkpay.reconciliation.fakes.InMemoryReconBreakRepository;
import com.sharkpay.reconciliation.fakes.InMemoryReconRunRepository;
import com.sharkpay.reconciliation.fakes.InMemorySettlementReportRepository;
import com.sharkpay.reconciliation.fakes.RecordingEventPublisher;
import com.sharkpay.reconciliation.fakes.SequentialRandomness;
import com.sharkpay.reconciliation.service.ApproveAndExecuteCompensationUseCase;
import com.sharkpay.reconciliation.service.GetBreakUseCase;
import com.sharkpay.reconciliation.service.GetReconRunUseCase;
import com.sharkpay.reconciliation.service.GetSettlementReportUseCase;
import com.sharkpay.reconciliation.service.ListBreaksUseCase;
import com.sharkpay.reconciliation.service.ProposeCompensationUseCase;
import com.sharkpay.reconciliation.service.SweepAgingBreaksUseCase;
import com.sharkpay.reconciliation.service.TransitionBreakUseCase;
import com.sharkpay.reconciliation.service.TriggerReconRunUseCase;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;

/**
 * Assembles the full reconciliation hexagon on in-tree fakes with a mutable
 * clock, shared by domain/service/standalone-MockMvc tests (no Spring
 * context, no database, per ADR 003). The wiring mirrors {@code ReconConfig}
 * bean-for-bean.
 */
public final class ReconTestEnv {

    public static final Instant START = Instant.parse("2026-09-01T10:00:00Z");
    /** Default window used by the seed helpers (half-open [FROM, TO)). */
    public static final Instant WINDOW_FROM = Instant.parse("2026-09-01T00:00:00Z");
    public static final Instant WINDOW_TO = Instant.parse("2026-09-02T00:00:00Z");
    public static final String PROVIDER = "honeycoin";

    public final MutableClock clock;
    public final SequentialRandomness randomness;
    public final FakeProviderStatement providers;
    public final FakeLedgerStatement ledgerStatement;
    public final FakeLedgerPort ledger;
    public final InMemoryReconRunRepository runs;
    public final InMemoryReconBreakRepository breaks;
    public final InMemorySettlementReportRepository reports;
    public final InMemoryCompensationEntryRepository compensations;
    public final InMemoryIdempotencyStore idempotency;
    public final RecordingEventPublisher events;
    public final ReconEvents eventFactory;

    public final TriggerReconRunUseCase triggerRun;
    public final GetReconRunUseCase getRun;
    public final GetBreakUseCase getBreak;
    public final ListBreaksUseCase listBreaks;
    public final TransitionBreakUseCase transitionBreak;
    public final ProposeCompensationUseCase proposeCompensation;
    public final ApproveAndExecuteCompensationUseCase approveAndExecute;
    public final SweepAgingBreaksUseCase sweepAging;
    public final GetSettlementReportUseCase settlementReports;

    public final ReconRunsController runsController;
    public final ReconBreaksController breaksController;
    public final CompensationsController compensationsController;
    public final SettlementReportsController settlementReportsController;
    public final GlobalExceptionHandler errorHandler;

    public ReconTestEnv() {
        this(START);
    }

    public ReconTestEnv(Instant start) {
        clock = new MutableClock(start);
        randomness = new SequentialRandomness();
        providers = new FakeProviderStatement();
        ledgerStatement = new FakeLedgerStatement();
        ledger = new FakeLedgerPort();
        runs = new InMemoryReconRunRepository();
        breaks = new InMemoryReconBreakRepository();
        reports = new InMemorySettlementReportRepository();
        compensations = new InMemoryCompensationEntryRepository();
        idempotency = new InMemoryIdempotencyStore();
        events = new RecordingEventPublisher();
        eventFactory = new ReconEvents(randomness);

        triggerRun = new TriggerReconRunUseCase(providers, ledgerStatement, runs, breaks, reports,
                idempotency, events, eventFactory, randomness, clock);
        getRun = new GetReconRunUseCase(runs, breaks, clock);
        getBreak = new GetBreakUseCase(breaks, clock);
        listBreaks = new ListBreaksUseCase(breaks, clock);
        transitionBreak = new TransitionBreakUseCase(breaks, clock);
        proposeCompensation = new ProposeCompensationUseCase(compensations, breaks, idempotency,
                randomness, clock);
        approveAndExecute = new ApproveAndExecuteCompensationUseCase(compensations, breaks, ledger,
                events, eventFactory, randomness, clock);
        sweepAging = new SweepAgingBreaksUseCase(breaks, events, eventFactory, clock);
        settlementReports = new GetSettlementReportUseCase(reports);

        runsController = new ReconRunsController(triggerRun, getRun, settlementReports, runs, clock);
        breaksController = new ReconBreaksController(getBreak, listBreaks, transitionBreak,
                proposeCompensation, compensations);
        compensationsController = new CompensationsController(approveAndExecute);
        settlementReportsController = new SettlementReportsController(settlementReports);
        errorHandler = new GlobalExceptionHandler();
    }

    /**
     * Seeds one matching pair (identical on both sides — a clean pair) into
     * the default window: no break.
     */
    public void seedMatch(String ref, long amountMinor, String currency, long feeMinor) {
        seedProviderLine(ref, "CONFIRMED", amountMinor, currency, feeMinor);
        seedInternalLine("int_" + ref, ref, "CONFIRMED", amountMinor, currency, feeMinor);
    }

    /** Currency-free overload (KES default) — the simplified seeding API. */
    public void seedProviderLine(String ref, String status, long amountMinor, long feeMinor) {
        seedProviderLine(ref, status, amountMinor, "KES", feeMinor);
    }

    public void seedProviderLine(String ref, String status, long amountMinor, String currency,
                                 long feeMinor) {
        providers.seed(ref, status, amountMinor, currency, feeMinor, midWindow());
    }

    /** Currency-free overload (KES default) — the simplified seeding API. */
    public void seedInternalLine(String internalRef, String providerRef, String status,
                                 long amountMinor, long feeMinor) {
        seedInternalLine(internalRef, providerRef, status, amountMinor, "KES", feeMinor);
    }

    public void seedInternalLine(String internalRef, String providerRef, String status,
                                 long amountMinor, String currency, long feeMinor) {
        ledgerStatement.seed(internalRef, providerRef, status, amountMinor, currency, feeMinor,
                midWindow());
    }

    private static Instant midWindow() {
        return Instant.parse("2026-09-01T12:00:00Z");
    }

    /** Triggers a run over the default window for the default provider. */
    public TriggerReconRunUseCase.Result triggerDefault(String idempotencyKey) {
        return triggerRun.trigger(idempotencyKey, PROVIDER, WINDOW_FROM, WINDOW_TO);
    }

    /**
     * Standalone MockMvc with a Jackson 3 (tools.jackson) JSON mapper:
     * ISO-8601 instants (Jackson 3 default) and NON_NULL inclusion — absent
     * money sides, failure_reason on completed runs and list-mode breaks are
     * omitted, matching the internal recon API's field shapes. Boot 4 =
     * Jackson 3: no com.fasterxml.jackson.databind anywhere.
     */
    public MockMvc mockMvc() {
        JsonMapper mapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(value ->
                        value.withValueInclusion(JsonInclude.Include.NON_NULL))
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders
                .standaloneSetup(runsController, breaksController, compensationsController,
                        settlementReportsController)
                .setControllerAdvice(errorHandler)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .setValidator(validator)
                .build();
    }
}
