package com.sharkpay.payments.config;

import com.sharkpay.money.Money;
import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
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
import com.sharkpay.payments.testsupport.PaymentsTestEnv;
import com.sharkpay.payments.workflow.PaymentActivitiesImpl;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the production {@link PaymentsConfig} bean factories without a
 * Spring context (mirrors the wallet exemplar's WalletConfigTest): every
 * factory must build a usable object, the cross-service port placeholders
 * fail fast and loud (money-path honesty, ADR 003 §3), and the storage-backed
 * port beans are satisfied at runtime by the component-scanned JPA adapters
 * (covered by JpaAdaptersTest). Use-case behavior is proven on the in-tree
 * test fakes, which mirror those adapters.
 */
class PaymentsConfigTest {

    private final PaymentsTestEnv env = new PaymentsTestEnv();
    private final PaymentsConfig config = new PaymentsConfig();

    @Test
    void clockBeanReturnsUtcNow() {
        Clock clock = config.clock();
        Instant before = Instant.now().minusSeconds(5);
        Instant after = Instant.now().plusSeconds(5);
        assertThat(clock.instant()).isBetween(before, after);
    }

    @Test
    void eventPublisherBeanIsTheLoggingPlaceholder() {
        EventPublisher publisher = config.eventPublisher();
        assertThat(publisher).isInstanceOf(LoggingEventPublisher.class);
    }

    @Test
    void lifecycleBeanIsTheLoggingPlaceholderWhileTemporalIsDisabled() {
        PaymentLifecyclePort lifecycle = config.paymentLifecyclePort();
        assertThat(lifecycle).isInstanceOf(LoggingPaymentLifecycle.class);
        // the guarded Temporal wiring (TemporalWorkerConfig) swaps this for
        // TemporalPaymentLifecycle when temporal.enabled=true — covered by
        // TemporalWorkerConfigTest / TemporalPaymentLifecycleTest
    }

    @Test
    void riskPortPlaceholderFailsFastAndLoud() {
        RiskPort risk = config.riskPort();
        assertThat(risk).isInstanceOf(IntegrationPendingRiskPort.class);
        UUID principalId = UUID.randomUUID();
        assertThatThrownBy(() -> risk.evaluate(new RiskPort.RiskEvaluation(principalId,
                "pay_000000000000000000001", Money.of(1_000L, "KES"), "honeycoin",
                PaymentsTestEnv.WALLET, RiskPort.Phase.PRE_AUTHORIZATION)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RiskPort adapter is not wired yet")
                .hasMessageContaining("pay_000000000000000000001");
    }

    @Test
    void walletHoldPortPlaceholderFailsFastAndLoudOnEveryOperation() {
        WalletHoldPort holds = config.walletHoldPort();
        assertThat(holds).isInstanceOf(IntegrationPendingWalletHoldPort.class);
        UUID sourceRef = UUID.randomUUID();
        assertThatThrownBy(() -> holds.walletExists(PaymentsTestEnv.WALLET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WalletHoldPort adapter is not wired yet");
        assertThatThrownBy(() -> holds.placeHold(PaymentsTestEnv.WALLET,
                Money.of(1_000L, "KES"), sourceRef))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot placeHold");
        assertThatThrownBy(() -> holds.releaseHold("hld_1", sourceRef))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot releaseHold");
        assertThatThrownBy(() -> holds.captureHold("hld_1", Money.of(1_000L, "KES"), sourceRef))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot captureHold");
    }

    @Test
    void ledgerPortPlaceholderFailsFastAndLoudOnPostingAndReversal() {
        LedgerPort ledger = config.ledgerPort();
        assertThat(ledger).isInstanceOf(IntegrationPendingLedgerPort.class);
        UUID paymentId = UUID.randomUUID();
        assertThatThrownBy(() -> ledger.postEntry(paymentId, LedgerPort.EntryType.HOLD,
                PaymentsTestEnv.WALLET, Money.of(1_000L, "KES"), "hold"))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("LedgerPort adapter is not wired yet")
                        .hasMessageContaining("HOLD");
        assertThatThrownBy(() -> ledger.reverseEntry(paymentId, paymentId, "reversal"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot reverseEntry");
    }

    @Test
    void providerGatewayPortPlaceholderFailsFastAndLoudOnEveryOperation() {
        ProviderGatewayPort gateway = config.providerGatewayPort();
        assertThat(gateway).isInstanceOf(IntegrationPendingProviderGateway.class);
        assertThatThrownBy(gateway::candidates)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ProviderGatewayPort adapter is not wired yet");
        assertThatThrownBy(() -> gateway.quote(new ProviderGatewayPort.QuoteRequest(
                1_000L, "KES", "honeycoin", PaymentsTestEnv.WALLET)))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Cannot quote");
        assertThatThrownBy(() -> gateway.initiate(new ProviderGatewayPort.InitiateRequest(
                "tx-key", 1_000L, "KES", "honeycoin", PaymentsTestEnv.WALLET, java.util.Map.of())))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Cannot initiate");
        assertThatThrownBy(() -> gateway.poll(new ProviderGatewayPort.ProviderRef(
                "honeycoin", "hc_1")))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Cannot poll");
        assertThatThrownBy(() -> gateway.cancel(new ProviderGatewayPort.ProviderRef(
                "honeycoin", "hc_1")))
                        .isInstanceOf(IllegalStateException.class)
                        .hasMessageContaining("Cannot cancel");
    }

    @Test
    void randomnessBeanProducesWireFormedIds() {
        Randomness randomness = config.randomness();
        assertThat(randomness).isInstanceOf(SystemRandomness.class);
        assertThat(randomness.paymentId())
                .matches("^pay_[0-9A-Za-z]{20,}$");
        assertThat(randomness.requestId()).matches("^req_[0-9A-Za-z]+$");
        for (int i = 0; i < 8; i++) {
            UUID id = randomness.uuidV7();
            assertThat(id.version()).isEqualTo(7); // RFC 9562 time-ordered
            assertThat(id.toString())
                    .matches("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
        }
    }

    @Test
    void principalResolverBeanIsTheJwtSubjectResolver() {
        PrincipalResolver principals = config.principalResolver();
        assertThat(principals).isInstanceOf(JwtPrincipalResolver.class);
    }

    @Test
    void routerAndEventsBeansBuild() {
        assertThat(config.routerPolicy()).isNotNull();
        PaymentEvents events = config.paymentEvents(env.randomness);
        assertThat(events).isNotNull();
    }

    @Test
    void allUseCaseAndActivityBeanMethodsBuildWorkingObjects() {
        // storage-backed ports, satisfied here by their in-tree mirrors
        PaymentRepository payments = env.payments;
        IdempotencyStore idempotency = env.idempotency;
        EventPublisher events = env.events;
        Clock clock = env.clock;

        config.evaluateRiskUseCase(payments, env.risk, clock);
        config.placeHoldUseCase(payments, env.walletHolds, env.ledger,
                env.paymentEvents, events, clock);
        config.failPaymentUseCase(payments, env.walletHolds, env.ledger,
                env.paymentEvents, events, clock);
        config.expirePaymentUseCase(payments, env.walletHolds, env.ledger,
                env.paymentEvents, events, clock);
        CreatePaymentUseCase create = config.createPaymentUseCase(payments, idempotency,
                env.risk, env.walletHolds,
                config.placeHoldUseCase(payments, env.walletHolds, env.ledger, env.paymentEvents,
                        events, clock),
                config.providerHandoffUseCase(payments, env.gateway, config.routerPolicy(), clock,
                        PaymentsTestEnv.REGION),
                config.failPaymentUseCase(payments, env.walletHolds, env.ledger, env.paymentEvents,
                        events, clock),
                env.lifecycle, config.paymentEvents(env.randomness), events, env.randomness, clock);
        CancelPaymentUseCase cancel = config.cancelPaymentUseCase(payments, env.walletHolds,
                env.ledger, idempotency, clock);
        config.recordProviderResultUseCase(payments, env.walletHolds, env.ledger, env.risk,
                idempotency, env.paymentEvents, events, clock);
        config.reversePaymentUseCase(payments, env.ledger, idempotency, env.paymentEvents,
                events, clock);
        config.getPaymentUseCase(payments);
        config.listPaymentsUseCase(payments);
        PaymentActivitiesImpl activities = config.paymentActivities(
                config.evaluateRiskUseCase(payments, env.risk, clock),
                config.placeHoldUseCase(payments, env.walletHolds, env.ledger, env.paymentEvents,
                        events, clock),
                config.providerHandoffUseCase(payments, env.gateway, config.routerPolicy(), clock,
                        PaymentsTestEnv.REGION),
                config.recordProviderResultUseCase(payments, env.walletHolds, env.ledger,
                        env.risk, idempotency, env.paymentEvents, events, clock),
                config.failPaymentUseCase(payments, env.walletHolds, env.ledger, env.paymentEvents,
                        events, clock),
                config.expirePaymentUseCase(payments, env.walletHolds, env.ledger, env.paymentEvents,
                        events, clock),
                config.getPaymentUseCase(payments), env.gateway, 2_000L);
        assertThat(activities).isNotNull();

        // smoke: the config-built wiring runs the whole synchronous prefix
        // end to end (risk → hold → initiate) on the fake ports, exactly as
        // the JPA adapters would satisfy it in production
        CreatePaymentUseCase.Result result = create.create("config-key-1",
                env.principals.principalId(), 150_000L, "KES", PaymentsTestEnv.WALLET,
                "honeycoin", java.util.Map.of(), null);
        assertThat(result.intent().state()).isEqualTo(PaymentState.PENDING_PROVIDER);
        PaymentIntent cancelled = cancel.cancel("config-cancel-1", result.intent().id()).intent();
        assertThat(cancelled.state()).isEqualTo(PaymentState.CANCELLED);
    }
}
