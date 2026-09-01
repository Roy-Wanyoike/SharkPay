package com.sharkpay.fx.config;

import com.sharkpay.fx.domain.MarkupPolicy;
import com.sharkpay.fx.ports.ConversionRepository;
import com.sharkpay.fx.ports.EventPublisher;
import com.sharkpay.fx.ports.IdempotencyStore;
import com.sharkpay.fx.ports.LedgerPort;
import com.sharkpay.fx.ports.QuoteRepository;
import com.sharkpay.fx.ports.RateProvider;
import com.sharkpay.fx.service.ConvertUseCase;
import com.sharkpay.fx.service.CreateQuoteUseCase;
import com.sharkpay.fx.service.ExpireQuotesUseCase;
import com.sharkpay.fx.service.LockQuoteUseCase;
import com.sharkpay.fx.service.ReconcilePositionsUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;

/**
 * Production wiring of the hexagon, mirroring the wallet service's
 * {@code WalletConfig}: use-case beans depend only on ports.
 *
 * <p>Port adapters:</p>
 * <ul>
 *   <li>storage-backed ports ({@link QuoteRepository},
 *       {@link ConversionRepository}, {@link IdempotencyStore}) — the JPA
 *       adapters in the storage package ({@code @Repository},
 *       component-scanned, Spring Data repositories against the
 *       Flyway-managed schema);</li>
 *   <li>{@link EventPublisher} — {@link LoggingEventPublisher} (structured
 *       logging) until the Kafka CloudEvent adapter lands;</li>
 *   <li>cross-service ports ({@link RateProvider}, {@link LedgerPort}) —
 *       fail-fast integration-pending placeholders until the REST adapters
 *       (providers rate feed, Go ledger posting API) are wired by the
 *       integrator (ADR 003 §3).</li>
 * </ul>
 *
 * <p>Local tests never boot this context: they assemble the same use-cases
 * on the in-tree fakes ({@code com.sharkpay.fx.fakes} in src/test).</p>
 */
@Configuration(proxyBeanMethods = false)
public class FxConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public EventPublisher eventPublisher() {
        return new LoggingEventPublisher();
    }

    @Bean
    public RateProvider rateProvider() {
        return new IntegrationPendingRateProvider();
    }

    @Bean
    public LedgerPort ledgerPort() {
        return new IntegrationPendingLedgerPort();
    }

    @Bean
    public MarkupPolicy markupPolicy(@Value("${fx.markup-bps:150}") long markupBps) {
        return new MarkupPolicy(markupBps);
    }

    @Bean
    public CreateQuoteUseCase createQuoteUseCase(RateProvider rateProvider, MarkupPolicy markupPolicy,
                                                 QuoteRepository quotes, Clock clock,
                                                 @Value("${fx.quote.default-ttl-seconds:30}") long defaultTtlSeconds) {
        return new CreateQuoteUseCase(rateProvider, markupPolicy, quotes, clock,
                Duration.ofSeconds(defaultTtlSeconds));
    }

    @Bean
    public LockQuoteUseCase lockQuoteUseCase(QuoteRepository quotes, EventPublisher events, Clock clock) {
        return new LockQuoteUseCase(quotes, events, clock);
    }

    @Bean
    public ConvertUseCase convertUseCase(QuoteRepository quotes, ConversionRepository conversions, LedgerPort ledger,
                                         IdempotencyStore idempotency, EventPublisher events, Clock clock) {
        return new ConvertUseCase(quotes, conversions, ledger, idempotency, events, clock);
    }

    @Bean
    public ExpireQuotesUseCase expireQuotesUseCase(QuoteRepository quotes, Clock clock) {
        return new ExpireQuotesUseCase(quotes, clock);
    }

    @Bean
    public ReconcilePositionsUseCase reconcilePositionsUseCase(ConversionRepository conversions,
                                                               LedgerPort ledger) {
        return new ReconcilePositionsUseCase(conversions, ledger);
    }
}
