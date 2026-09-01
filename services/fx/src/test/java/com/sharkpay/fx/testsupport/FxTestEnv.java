package com.sharkpay.fx.testsupport;

import com.sharkpay.fx.api.FxController;
import com.sharkpay.fx.api.GlobalExceptionHandler;
import com.sharkpay.fx.domain.MarkupPolicy;
import com.sharkpay.fx.domain.Quote;
import com.sharkpay.fx.fakes.FakeLedgerPort;
import com.sharkpay.fx.fakes.FakeRateProvider;
import com.sharkpay.fx.fakes.InMemoryConversionRepository;
import com.sharkpay.fx.fakes.InMemoryIdempotencyStore;
import com.sharkpay.fx.fakes.InMemoryQuoteRepository;
import com.sharkpay.fx.fakes.RecordingEventPublisher;
import com.sharkpay.fx.service.ConvertUseCase;
import com.sharkpay.fx.service.CreateQuoteUseCase;
import com.sharkpay.fx.service.ExpireQuotesUseCase;
import com.sharkpay.fx.service.LockQuoteUseCase;
import com.sharkpay.fx.service.ReconcilePositionsUseCase;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;

/**
 * Assembles the full FX object graph on in-memory fakes with a mutable
 * clock, shared by service and standalone-MockMvc controller tests
 * (no Spring context, per ADR 003 — no @SpringBootTest).
 */
public final class FxTestEnv {

    public static final Instant START = Instant.parse("2026-09-01T10:00:00Z");

    public final MutableClock clock;
    public final FakeRateProvider rates;
    public final InMemoryQuoteRepository quotes;
    public final InMemoryConversionRepository conversions;
    public final FakeLedgerPort ledger;
    public final InMemoryIdempotencyStore idempotency;
    public final RecordingEventPublisher events;
    public final MarkupPolicy markup;
    public final CreateQuoteUseCase createQuote;
    public final LockQuoteUseCase lockQuote;
    public final ConvertUseCase convert;
    public final ExpireQuotesUseCase expireQuotes;
    public final ReconcilePositionsUseCase reconcile;
    public final FxController controller;
    public final GlobalExceptionHandler errorHandler;

    public FxTestEnv() {
        this(START, 150, 30);
    }

    public FxTestEnv(Instant start, long markupBps, long defaultTtlSeconds) {
        clock = new MutableClock(start);
        rates = new FakeRateProvider();
        quotes = new InMemoryQuoteRepository();
        conversions = new InMemoryConversionRepository();
        ledger = new FakeLedgerPort();
        idempotency = new InMemoryIdempotencyStore();
        events = new RecordingEventPublisher();
        markup = new MarkupPolicy(markupBps);
        createQuote = new CreateQuoteUseCase(rates, markup, quotes, clock, Duration.ofSeconds(defaultTtlSeconds));
        lockQuote = new LockQuoteUseCase(quotes, events, clock);
        convert = new ConvertUseCase(quotes, conversions, ledger, idempotency, events, clock);
        expireQuotes = new ExpireQuotesUseCase(quotes, clock);
        reconcile = new ReconcilePositionsUseCase(conversions, ledger);
        controller = new FxController(createQuote, lockQuote, convert, quotes, conversions);
        errorHandler = new GlobalExceptionHandler();
    }

    /** Creates a QUOTED quote (default TTL). */
    public Quote newQuote(String base, String quote, long amountMinor) {
        return createQuote.create(amountMinor, base, quote, null).quote();
    }

    /** Creates and locks a quote (rate guaranteed). */
    public Quote newLockedQuote(String base, String quote, long amountMinor) {
        Quote quoteObject = newQuote(base, quote, amountMinor);
        return lockQuote.lock(quoteObject.id()).quote();
    }

    /** Standalone MockMvc with the contract ISO-8601 JSON mapper (Jackson 3)
     *  and bean validation, mirroring the identity controller test setup. */
    public MockMvc mockMvc() {
        JsonMapper mapper = JsonMapper.builder().build();
        org.springframework.validation.beanvalidation.LocalValidatorFactoryBean validator =
                new org.springframework.validation.beanvalidation.LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(controller)
                .setControllerAdvice(errorHandler)
                .setValidator(validator)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .build();
    }
}
