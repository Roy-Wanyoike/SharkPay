package com.sharkpay.fx.config;

import com.sharkpay.fx.domain.MarkupPolicy;
import com.sharkpay.fx.ports.EventPublisher;
import com.sharkpay.fx.ports.LedgerPort;
import com.sharkpay.fx.ports.RateProvider;
import com.sharkpay.fx.service.ConvertUseCase;
import com.sharkpay.fx.service.CreateQuoteUseCase;
import com.sharkpay.fx.service.ExpireQuotesUseCase;
import com.sharkpay.fx.service.LockQuoteUseCase;
import com.sharkpay.fx.service.ReconcilePositionsUseCase;
import com.sharkpay.fx.testsupport.FxTestEnv;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the production {@link FxConfig} bean factories without a Spring
 * context: every factory must build a usable object, the cross-service port
 * placeholders fail fast and loud (money path honesty, ADR 003 §3), and the
 * storage-backed port beans are satisfied by component-scanned JPA adapters
 * at runtime (covered by JpaAdaptersTest). Use-case behavior is proven on
 * the test-tree fakes, which mirror those adapters — mirroring the wallet
 * service's WalletConfigTest.
 */
class FxConfigTest {

    private final FxTestEnv env = new FxTestEnv();
    private final FxConfig config = new FxConfig();

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
    void rateProviderPlaceholderFailsFastAndLoud() {
        RateProvider rates = config.rateProvider();
        assertThat(rates).isInstanceOf(IntegrationPendingRateProvider.class);
        assertThatThrownBy(() -> rates.rawRate("USD", "KES"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("RateProvider adapter is not wired yet")
                .hasMessageContaining("USD/KES");
    }

    @Test
    void ledgerPortPlaceholderFailsFastAndLoud() {
        LedgerPort ledger = config.ledgerPort();
        assertThat(ledger).isInstanceOf(IntegrationPendingLedgerPort.class);
        assertThatThrownBy(() -> ledger.postTransaction("fx:cnv_x", java.util.List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LedgerPort adapter is not wired yet")
                .hasMessageContaining("fx:cnv_x");
        assertThatThrownBy(() -> ledger.getStatement("fx-position:USD"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LedgerPort adapter is not wired yet")
                .hasMessageContaining("fx-position:USD");
    }

    @Test
    void markupPolicyBeanReadsTheConfiguredBps() {
        assertThat(config.markupPolicy(250)).isEqualTo(new MarkupPolicy(250));
        assertThat(config.markupPolicy(250).markupBps()).isEqualTo(250);
    }

    @Test
    void allUseCaseBeanMethodsBuildWorkingObjects() {
        // storage-backed ports, satisfied here by their in-tree mirrors
        MarkupPolicy markup = config.markupPolicy(150);
        CreateQuoteUseCase createQuote = config.createQuoteUseCase(env.rates, markup, env.quotes,
                env.clock, 30);
        LockQuoteUseCase lockQuote = config.lockQuoteUseCase(env.quotes, env.events, env.clock);
        ConvertUseCase convert = config.convertUseCase(env.quotes, env.conversions, env.ledger,
                env.idempotency, env.events, env.clock);
        ExpireQuotesUseCase expireQuotes = config.expireQuotesUseCase(env.quotes, env.clock);
        ReconcilePositionsUseCase reconcile = config.reconcilePositionsUseCase(env.conversions, env.ledger);

        // smoke: the whole wiring works end to end (quote → lock → convert →
        // reconcile — the same money-safety path as the service tests)
        String quoteId = createQuote.create(10_000, "USD", "KES", null).quote().id();
        lockQuote.lock(quoteId);
        ConvertUseCase.Result result = convert.convert("config-smoke-1", quoteId,
                "wallet/src-USD", "wallet/dst-KES");
        assertThat(result.conversion().ledgerEntryId()).isNotBlank();
        assertThat(reconcile.reconcile())
                .extracting(report -> report.currency())
                .containsExactlyInAnyOrder("KES", "USD");
        assertThat(expireQuotes.expireOverdue()).isZero();
    }

    @Test
    void factoriesAcceptAnyPortImplementation() {
        // the config never references the fakes: its port parameters are the
        // port interfaces themselves (satisfied by JPA adapters at runtime).
        // Wiring the fake instances through the same factories proves the
        // factory signatures accept any port implementation.
        CreateQuoteUseCase createQuote = config.createQuoteUseCase(env.rates,
                config.markupPolicy(150), env.quotes, env.clock, 30);
        assertThat(createQuote.create(100, "EUR", "USD", null).quote().baseCurrency())
                .isEqualTo("EUR");
    }
}
