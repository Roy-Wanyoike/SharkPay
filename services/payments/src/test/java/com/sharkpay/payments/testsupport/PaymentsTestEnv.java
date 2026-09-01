package com.sharkpay.payments.testsupport;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sharkpay.payments.api.GlobalExceptionHandler;
import com.sharkpay.payments.api.InternalLifecycleController;
import com.sharkpay.payments.api.PaymentController;
import com.sharkpay.payments.config.PaymentsConfig;
import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.RouterPolicy;
import com.sharkpay.payments.events.PaymentEvents;
import com.sharkpay.payments.fakes.FakeLedgerPort;
import com.sharkpay.payments.fakes.FakeProviderGateway;
import com.sharkpay.payments.fakes.FakeRiskPort;
import com.sharkpay.payments.fakes.FakeWalletHoldPort;
import com.sharkpay.payments.fakes.FixedPrincipalResolver;
import com.sharkpay.payments.fakes.InMemoryIdempotencyStore;
import com.sharkpay.payments.fakes.InMemoryPaymentRepository;
import com.sharkpay.payments.fakes.RecordingEventPublisher;
import com.sharkpay.payments.fakes.RecordingPaymentLifecycle;
import com.sharkpay.payments.fakes.SequentialRandomness;
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
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Assembles the full payments hexagon on in-memory fakes with a mutable
 * clock, shared by domain/service/standalone-MockMvc workflow tests (no
 * Spring context, no database, no Temporal server, per ADR 003). The wiring
 * mirrors {@link PaymentsConfig} bean-for-bean.
 */
public final class PaymentsTestEnv {

    public static final Instant START = Instant.parse("2026-09-01T10:00:00Z");
    /** A registered destination wallet id (matches ^wal_[0-9A-Za-z]{20,}$). */
    public static final String WALLET = "wal_0123456789abcdef0123456789abcdef";
    /** Default router region (application.yml payments.router.default-region). */
    public static final String REGION = "KE";
    /** Provider poll cadence used by the workflow loop (temporal.poll-interval-ms). */
    public static final long POLL_INTERVAL_MS = 2000L;

    public final MutableClock clock;
    public final SequentialRandomness randomness;
    public final FakeRiskPort risk;
    public final FakeWalletHoldPort walletHolds;
    public final FakeLedgerPort ledger;
    public final FakeProviderGateway gateway;
    public final InMemoryPaymentRepository payments;
    public final InMemoryIdempotencyStore idempotency;
    public final RecordingEventPublisher events;
    public final RecordingPaymentLifecycle lifecycle;
    public final PaymentEvents paymentEvents;
    public final RouterPolicy router;
    public final FixedPrincipalResolver principals;

    public final EvaluateRiskUseCase evaluateRisk;
    public final PlaceHoldUseCase placeHold;
    public final ProviderHandoffUseCase handoff;
    public final RecordProviderResultUseCase recordResult;
    public final FailPaymentUseCase failPayment;
    public final ExpirePaymentUseCase expirePayment;
    public final CreatePaymentUseCase createPayment;
    public final CancelPaymentUseCase cancelPayment;
    public final ReversePaymentUseCase reversePayment;
    public final GetPaymentUseCase getPayment;
    public final ListPaymentsUseCase listPayments;

    public final PaymentController paymentController;
    public final InternalLifecycleController internalLifecycleController;
    public final GlobalExceptionHandler errorHandler;
    public final PaymentActivitiesImpl activities;

    public PaymentsTestEnv() {
        this(START);
    }

    public PaymentsTestEnv(Instant start) {
        clock = new MutableClock(start);
        randomness = new SequentialRandomness();
        risk = new FakeRiskPort();
        walletHolds = new FakeWalletHoldPort().addWallet(WALLET);
        ledger = new FakeLedgerPort();
        gateway = new FakeProviderGateway().addCandidate(FakeProviderGateway.honeycoin());
        payments = new InMemoryPaymentRepository();
        idempotency = new InMemoryIdempotencyStore();
        events = new RecordingEventPublisher();
        lifecycle = new RecordingPaymentLifecycle();
        paymentEvents = new PaymentEvents(randomness);
        router = new RouterPolicy();
        principals = FixedPrincipalResolver.random();

        evaluateRisk = new EvaluateRiskUseCase(payments, risk, clock);
        placeHold = new PlaceHoldUseCase(payments, walletHolds, ledger, paymentEvents, events,
                clock);
        handoff = new ProviderHandoffUseCase(payments, gateway, router, clock, REGION);
        recordResult = new RecordProviderResultUseCase(payments, walletHolds, ledger, risk,
                idempotency, paymentEvents, events, clock);
        failPayment = new FailPaymentUseCase(payments, walletHolds, ledger, paymentEvents, events,
                clock);
        expirePayment = new ExpirePaymentUseCase(payments, walletHolds, ledger, paymentEvents,
                events, clock);
        createPayment = new CreatePaymentUseCase(payments, idempotency, risk, walletHolds,
                placeHold, handoff, failPayment, lifecycle, paymentEvents, events, randomness,
                clock);
        cancelPayment = new CancelPaymentUseCase(payments, walletHolds, ledger, idempotency,
                clock);
        reversePayment = new ReversePaymentUseCase(payments, ledger, idempotency, paymentEvents,
                events, clock);
        getPayment = new GetPaymentUseCase(payments);
        listPayments = new ListPaymentsUseCase(payments);

        paymentController = new PaymentController(createPayment, cancelPayment, getPayment,
                listPayments, principals, randomness);
        internalLifecycleController = new InternalLifecycleController(recordResult, reversePayment,
                listPayments);
        errorHandler = new GlobalExceptionHandler();
        activities = new PaymentActivitiesImpl(evaluateRisk, placeHold, handoff, recordResult,
                failPayment, expirePayment, getPayment, gateway, POLL_INTERVAL_MS);
    }

    /** Creates a KES 150 000 honeycoin intent for the registered wallet. */
    public PaymentIntent createDefault() {
        return create("key-1");
    }

    /** Creates a KES 150 000 honeycoin intent for the registered wallet. */
    public PaymentIntent create(String idempotencyKey) {
        return create(idempotencyKey, principals.principalId());
    }

    /** Creates a KES 150 000 honeycoin intent for the registered wallet. */
    public PaymentIntent create(String idempotencyKey, UUID principalId) {
        return createPayment.create(idempotencyKey, principalId, 150_000L, "KES", WALLET,
                "honeycoin", java.util.Map.of(), null).intent();
    }

    /** The mutable clock advanced by one poll interval (workflow-friendly). */
    public void tickPoll() {
        clock.advance(java.time.Duration.ofMillis(POLL_INTERVAL_MS));
    }

    /**
     * Standalone MockMvc with a Jackson 3 (tools.jackson) JSON mapper:
     * ISO-8601 instants (Jackson 3 default) and NON_NULL inclusion — optional
     * fields (metadata, failure_reason, provider_ref, next_cursor) are
     * omitted, matching payments.yaml additionalProperties: false. Boot 4 =
     * Jackson 3: no com.fasterxml.jackson.databind anywhere.
     */
    public MockMvc mockMvc() {
        JsonMapper mapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(value ->
                        value.withValueInclusion(JsonInclude.Include.NON_NULL))
                .enable(tools.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(paymentController, internalLifecycleController)
                .setControllerAdvice(errorHandler)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .setValidator(validator)
                .build();
    }
}
