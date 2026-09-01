package com.sharkpay.payments.config;

import com.sharkpay.payments.domain.RouterPolicy;
import com.sharkpay.payments.events.PaymentEvents;
import com.sharkpay.payments.ports.EventPublisher;
import com.sharkpay.payments.ports.IdempotencyStore;
import com.sharkpay.payments.ports.LedgerPort;
import com.sharkpay.payments.ports.PaymentLifecyclePort;
import com.sharkpay.payments.ports.PaymentRepository;
import com.sharkpay.payments.ports.PrincipalResolver;
import com.sharkpay.payments.ports.ProviderGatewayPort;
import com.sharkpay.payments.ports.Randomness;
import com.sharkpay.payments.ports.RiskPort;
import com.sharkpay.payments.ports.WalletHoldPort;
import com.sharkpay.payments.service.CancelPaymentUseCase;
import com.sharkpay.payments.service.CreatePaymentUseCase;
import com.sharkpay.payments.service.EvaluateRiskUseCase;
import com.sharkpay.payments.service.ExpirePaymentUseCase;
import com.sharkpay.payments.service.FailPaymentUseCase;
import com.sharkpay.payments.service.GetPaymentUseCase;
import com.sharkpay.payments.service.ListPaymentsUseCase;
import com.sharkpay.payments.service.PlaceHoldUseCase;
import com.sharkpay.payments.service.ProviderHandoffUseCase;
import com.sharkpay.payments.service.RecordProviderResultUseCase;
import com.sharkpay.payments.service.ReversePaymentUseCase;
import com.sharkpay.payments.workflow.PaymentActivitiesImpl;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Production wiring of the payments hexagon, mirroring the wallet service's
 * {@code WalletConfig}: use-case beans depend only on ports.
 *
 * <p>Port adapters:</p>
 * <ul>
 *   <li>storage-backed ports ({@link PaymentRepository},
 *       {@link IdempotencyStore}) — the JPA adapters in the storage package
 *       ({@code @Repository}, component-scanned, against the Flyway-managed
 *       schema);</li>
 *   <li>{@link EventPublisher} — {@link LoggingEventPublisher} (structured
 *       logging) until the NATS/Kafka CloudEvent adapter lands;</li>
 *   <li>{@link PaymentLifecyclePort} — {@link LoggingPaymentLifecycle} while
 *       {@code temporal.enabled=false}; {@code TemporalWorkerConfig} swaps in
 *       the real Temporal lifecycle (and starts the worker on task queue
 *       {@code payments}) when the flag is on;</li>
 *   <li>cross-service ports ({@link RiskPort}, {@link WalletHoldPort},
 *       {@link LedgerPort}, {@link ProviderGatewayPort}) — fail-fast
 *       integration-pending placeholders until the REST adapters (risk
 *       evaluation, wallet funds control, Go ledger posting, Go provider
 *       gateway) are wired by the integrator (ADR 003 §3).</li>
 * </ul>
 *
 * <p>Local tests never boot this context: they assemble the same use-cases
 * on the in-tree fakes ({@code com.sharkpay.payments.fakes} in src/test) and
 * drive the workflow through TestWorkflowEnvironment.</p>
 */
@Configuration(proxyBeanMethods = false)
public class PaymentsConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public Randomness randomness() {
        return new SystemRandomness();
    }

    @Bean
    public PrincipalResolver principalResolver() {
        return new JwtPrincipalResolver();
    }

    @Bean
    public EventPublisher eventPublisher() {
        return new LoggingEventPublisher();
    }

    @Bean
    public PaymentLifecyclePort paymentLifecyclePort() {
        return new LoggingPaymentLifecycle();
    }

    @Bean
    public RiskPort riskPort() {
        return new IntegrationPendingRiskPort();
    }

    @Bean
    public WalletHoldPort walletHoldPort() {
        return new IntegrationPendingWalletHoldPort();
    }

    @Bean
    public LedgerPort ledgerPort() {
        return new IntegrationPendingLedgerPort();
    }

    @Bean
    public ProviderGatewayPort providerGatewayPort() {
        return new IntegrationPendingProviderGateway();
    }

    @Bean
    public RouterPolicy routerPolicy() {
        return new RouterPolicy();
    }

    @Bean
    public PaymentEvents paymentEvents(Randomness randomness) {
        return new PaymentEvents(randomness);
    }

    @Bean
    public EvaluateRiskUseCase evaluateRiskUseCase(PaymentRepository payments, RiskPort risk,
                                                   Clock clock) {
        return new EvaluateRiskUseCase(payments, risk, clock);
    }

    @Bean
    public PlaceHoldUseCase placeHoldUseCase(PaymentRepository payments, WalletHoldPort walletHolds,
                                             LedgerPort ledger, PaymentEvents events,
                                             EventPublisher publisher, Clock clock) {
        return new PlaceHoldUseCase(payments, walletHolds, ledger, events, publisher, clock);
    }

    @Bean
    public ProviderHandoffUseCase providerHandoffUseCase(PaymentRepository payments,
                                                         ProviderGatewayPort gateway,
                                                         RouterPolicy router, Clock clock,
                                                         @Value("${payments.router.default-region:KE}")
                                                         String defaultRegion) {
        return new ProviderHandoffUseCase(payments, gateway, router, clock, defaultRegion);
    }

    @Bean
    public FailPaymentUseCase failPaymentUseCase(PaymentRepository payments,
                                                 WalletHoldPort walletHolds, LedgerPort ledger,
                                                 PaymentEvents events, EventPublisher publisher,
                                                 Clock clock) {
        return new FailPaymentUseCase(payments, walletHolds, ledger, events, publisher, clock);
    }

    @Bean
    public ExpirePaymentUseCase expirePaymentUseCase(PaymentRepository payments,
                                                     WalletHoldPort walletHolds, LedgerPort ledger,
                                                     PaymentEvents events,
                                                     EventPublisher publisher, Clock clock) {
        return new ExpirePaymentUseCase(payments, walletHolds, ledger, events, publisher, clock);
    }

    @Bean
    public CreatePaymentUseCase createPaymentUseCase(PaymentRepository payments,
                                                      IdempotencyStore idempotency,
                                                      RiskPort risk, WalletHoldPort walletHolds,
                                                      PlaceHoldUseCase placeHold,
                                                      ProviderHandoffUseCase handoff,
                                                      FailPaymentUseCase failPayment,
                                                      PaymentLifecyclePort lifecycle,
                                                      PaymentEvents events,
                                                      EventPublisher publisher,
                                                      Randomness randomness, Clock clock) {
        return new CreatePaymentUseCase(payments, idempotency, risk, walletHolds, placeHold,
                handoff, failPayment, lifecycle, events, publisher, randomness, clock);
    }

    @Bean
    public CancelPaymentUseCase cancelPaymentUseCase(PaymentRepository payments,
                                                     WalletHoldPort walletHolds,
                                                     LedgerPort ledger,
                                                     IdempotencyStore idempotency, Clock clock) {
        return new CancelPaymentUseCase(payments, walletHolds, ledger, idempotency, clock);
    }

    @Bean
    public RecordProviderResultUseCase recordProviderResultUseCase(PaymentRepository payments,
                                                                   WalletHoldPort walletHolds,
                                                                   LedgerPort ledger, RiskPort risk,
                                                                   IdempotencyStore idempotency,
                                                                   PaymentEvents events,
                                                                   EventPublisher publisher,
                                                                   Clock clock) {
        return new RecordProviderResultUseCase(payments, walletHolds, ledger, risk, idempotency,
                events, publisher, clock);
    }

    @Bean
    public ReversePaymentUseCase reversePaymentUseCase(PaymentRepository payments,
                                                       LedgerPort ledger,
                                                       IdempotencyStore idempotency,
                                                       PaymentEvents events,
                                                       EventPublisher publisher, Clock clock) {
        return new ReversePaymentUseCase(payments, ledger, idempotency, events, publisher, clock);
    }

    @Bean
    public GetPaymentUseCase getPaymentUseCase(PaymentRepository payments) {
        return new GetPaymentUseCase(payments);
    }

    @Bean
    public ListPaymentsUseCase listPaymentsUseCase(PaymentRepository payments) {
        return new ListPaymentsUseCase(payments);
    }

    /**
     * Temporal activity implementations over the use-cases. The workflow
     * worker (TemporalWorkerConfig, guarded by {@code temporal.enabled})
     * executes these; the poll cadence comes from the same property so
     * the workflow loop and the ops console agree.
     */
    @Bean
    public PaymentActivitiesImpl paymentActivities(EvaluateRiskUseCase evaluateRisk,
                                                   PlaceHoldUseCase placeHold,
                                                   ProviderHandoffUseCase handoff,
                                                   RecordProviderResultUseCase recordResult,
                                                   FailPaymentUseCase failPayment,
                                                   ExpirePaymentUseCase expirePayment,
                                                   GetPaymentUseCase getPayment,
                                                   ProviderGatewayPort gateway,
                                                   @Value("${temporal.poll-interval-ms:2000}")
                                                   long pollIntervalMs) {
        return new PaymentActivitiesImpl(evaluateRisk, placeHold, handoff, recordResult,
                failPayment, expirePayment, getPayment, gateway, pollIntervalMs);
    }
}
