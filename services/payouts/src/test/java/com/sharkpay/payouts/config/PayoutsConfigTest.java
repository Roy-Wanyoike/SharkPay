package com.sharkpay.payouts.config;

import com.sharkpay.money.Money;
import com.sharkpay.payouts.domain.BackoffPolicy;
import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.domain.PayoutFeePolicy;
import com.sharkpay.payouts.domain.PayoutState;
import com.sharkpay.payouts.domain.Transfer;
import com.sharkpay.payouts.domain.TransferState;
import com.sharkpay.payouts.ports.LedgerPort;
import com.sharkpay.payouts.ports.ProviderGatewayPort;
import com.sharkpay.payouts.ports.Randomness;
import com.sharkpay.payouts.ports.WalletHoldPort;
import com.sharkpay.payouts.testsupport.PayoutsTestEnv;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the production {@link PayoutsConfig} bean factories without a
 * Spring context (mirrors the payments exemplar's PaymentsConfigTest):
 * every factory must build a usable object, the cross-service port
 * placeholders fail fast and loud (money-path honesty, ADR 003 §3 — no
 * transfer or payout can move money against an unwired ledger), and the
 * storage-backed port beans are satisfied at runtime by the
 * component-scanned JPA adapters (covered by JpaAdaptersTest). Use-case
 * behavior is proven on the in-tree test fakes, which mirror those
 * adapters.
 */
class PayoutsConfigTest {

    private final PayoutsTestEnv env = new PayoutsTestEnv();
    private final PayoutsConfig config = new PayoutsConfig();

    @Test
    void clockBeanReturnsUtcNow() {
        Clock clock = config.clock();
        Instant before = Instant.now().minusSeconds(5);
        Instant after = Instant.now().plusSeconds(5);
        assertThat(clock.instant()).isBetween(before, after);
        assertThat(clock.getZone()).isEqualTo(java.time.ZoneOffset.UTC);
    }

    @Test
    void eventPublisherAndSchedulerBeansAreTheLoggingPlaceholders() {
        assertThat(config.eventPublisher()).isInstanceOf(LoggingEventPublisher.class);
        assertThat(config.schedulerPort()).isInstanceOf(LoggingSchedulerPort.class);
    }

    @Test
    void walletHoldPortPlaceholderFailsFastAndLoud() {
        WalletHoldPort wallets = config.walletHoldPort();
        assertThat(wallets).isInstanceOf(IntegrationPendingWalletHoldPort.class);
        assertThatThrownBy(() -> wallets.findWallet(PayoutsTestEnv.WALLET))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("WalletHoldPort adapter is not wired yet")
                .hasMessageContaining(PayoutsTestEnv.WALLET);
    }

    @Test
    void principalLookupPlaceholderFailsFastAndLoud() {
        assertThat(config.principalLookup()).isInstanceOf(IntegrationPendingPrincipalLookup.class);
        UUID principalId = UUID.randomUUID();
        assertThatThrownBy(() -> config.principalLookup().findById(principalId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PrincipalLookup adapter is not wired yet")
                .hasMessageContaining(principalId.toString());
    }

    @Test
    void ledgerPortPlaceholderFailsFastAndLoudOnPostingAndReversal() {
        LedgerPort ledger = config.ledgerPort();
        assertThat(ledger).isInstanceOf(IntegrationPendingLedgerPort.class);
        UUID sourceRef = UUID.randomUUID();
        LedgerPort.LedgerPosting posting = LedgerPort.LedgerPosting.of("payouts:pot_x:hold",
                LedgerPort.Source.PAYOUTS, sourceRef, LedgerPort.EntryType.HOLD, "hold",
                List.of(new LedgerPort.Leg("wallet-a", LedgerPort.Direction.DEBIT,
                                Money.of(1_000, "KES")),
                        new LedgerPort.Leg("payouts-clearing:KES", LedgerPort.Direction.CREDIT,
                                Money.of(1_000, "KES"))));
        assertThatThrownBy(() -> ledger.post(posting))
                .isInstanceOf(com.sharkpay.payouts.domain.LedgerPostingException.class)
                .hasMessageContaining("LedgerPort adapter is not wired yet")
                .hasMessageContaining("payouts:pot_x:hold")
                .hasMessageContaining("entry type hold")
                .hasMessageContaining("2 legs");
        assertThatThrownBy(() -> ledger.reverse(UUID.randomUUID(), "payouts:pot_x:release",
                sourceRef, "release"))
                .isInstanceOf(com.sharkpay.payouts.domain.LedgerPostingException.class)
                .hasMessageContaining("Cannot reverse entry");
    }

    @Test
    void providerGatewayPortPlaceholderFailsFastAndLoudOnEveryOperation() {
        ProviderGatewayPort gateway = config.providerGatewayPort();
        assertThat(gateway).isInstanceOf(IntegrationPendingProviderGateway.class);
        ProviderGatewayPort.ProviderRef ref = new ProviderGatewayPort.ProviderRef("honeycoin",
                "hc_1");
        assertThatThrownBy(() -> gateway.initiate(new ProviderGatewayPort.InitiateSubmission(
                "payouts:pot_x:submit", "pot_x", "mpesa", PayoutsTestEnv.mpesaDestination(),
                1_000L, "KES", 2, Map.of())))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("ProviderGatewayPort adapter is not wired yet")
                .hasMessageContaining("pot_x")
                .hasMessageContaining("mpesa");
        assertThatThrownBy(() -> gateway.poll(ref))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot poll honeycoin:hc_1");
        assertThatThrownBy(() -> gateway.cancel(ref))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot cancel honeycoin:hc_1");
    }

    @Test
    void randomnessBeanIsCSPRNGBackedAndBounded() {
        Randomness randomness = config.randomness();
        assertThat(randomness).isInstanceOf(Randomness.SecureRandomness.class);
        for (int i = 0; i < 32; i++) {
            assertThat(randomness.bounded(250)).isBetween(0L, 249L);
        }
        assertThatThrownBy(() -> randomness.bounded(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("bound must be positive");
    }

    @Test
    void feePolicyBeanBuildsTheConfiguredScheduleAndBackoffBeanTheBounds() {
        PayoutFeePolicy fees = config.payoutFeePolicy(5_500, 100, 3_000, 50, 250_000, 25);
        PayoutFeePolicy.Quote quote = fees.quote(com.sharkpay.payouts.domain.Rail.MPESA,
                Money.of(500_000, "KES"));
        assertThat(quote.fee()).isEqualTo(Money.of(10_500, "KES"));
        assertThat(quote.nonRefundable()).isEqualTo(Money.of(5_500, "KES"));

        BackoffPolicy backoff = config.backoffPolicy(1_000, 300_000, 250);
        assertThat(backoff.base()).isEqualTo(Duration.ofMillis(1_000));
        assertThat(backoff.cap()).isEqualTo(Duration.ofMillis(300_000));
        assertThat(backoff.jitterBound()).isEqualTo(Duration.ofMillis(250));
        // the default configuration satisfies the jitter ≤ cap/8 invariant
        assertThat(backoff.cap().dividedBy(8).compareTo(backoff.jitterBound()))
                .as("jitter bound must stay within cap/8").isPositive();
    }

    @Test
    void allUseCaseBeanMethodsBuildWorkingObjectsAndRunEndToEnd() {
        // storage-backed ports, satisfied here by their in-tree mirrors
        BackoffPolicy backoff = config.backoffPolicy(1_000, 300_000, 0);
        PayoutFeePolicy fees = config.payoutFeePolicy(5_500, 100, 3_000, 50, 250_000, 25);

        var createTransfer = config.createTransferUseCase(env.wallets, env.ledger, env.transfers,
                env.idempotency, env.events, env.clock);
        var createPayout = config.createPayoutUseCase(env.wallets, env.principals, env.ledger,
                env.payouts, env.idempotency, env.events, env.scheduler, fees, env.clock);
        var getPayout = config.getPayoutUseCase(env.payouts);
        var cancelPayout = config.cancelPayoutUseCase(env.payouts, env.ledger, env.idempotency,
                env.scheduler, env.clock);
        var providerResults = config.handleProviderResultUseCase(env.payouts, env.ledger,
                env.idempotency, env.events, env.clock);
        var riskDecisions = config.handleRiskDecisionUseCase(env.payouts, env.ledger, env.clock);
        var releaseDue = config.releaseDuePayoutsUseCase(env.payouts, env.gateway, env.ledger,
                env.events, backoff, env.randomness, env.clock, 50, 8);
        var expireOverdue = config.expirePayoutsUseCase(env.payouts, env.gateway, env.ledger,
                env.clock, 100);
        var pollInFlight = config.pollPayoutsUseCase(env.payouts, env.gateway, providerResults,
                100);
        var sweeper = new com.sharkpay.payouts.service.PayoutSweeper(releaseDue, expireOverdue,
                pollInFlight);
        assertThat(sweeper).isNotNull();

        // smoke: the config-built wiring runs money end to end on the fakes
        Transfer transfer = createTransfer.create("config-key-1", PayoutsTestEnv.WALLET,
                PayoutsTestEnv.OTHER_WALLET, 25_000L, "KES", Map.of()).transfer();
        assertThat(transfer.state()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(env.ledger.effectCount("transfers:" + transfer.id())).isEqualTo(1);

        Payout payout = createPayout.create("config-key-2", PayoutsTestEnv.WALLET, 500_000L,
                "KES", PayoutsTestEnv.mpesaDestination(), null, Map.of(), null, null).payout();
        assertThat(payout.state()).isEqualTo(PayoutState.PENDING_RISK);
        assertThat(getPayout.get(payout.id()).id()).isEqualTo(payout.id());

        env.clock.advance(Duration.ofSeconds(1));
        assertThat(releaseDue.releaseDue().submitted()).isEqualTo(1);
        providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.SUCCEEDED, null,
                null, null, null, null);
        assertThat(env.payouts.findById(payout.id()).orElseThrow().state())
                .isEqualTo(PayoutState.SUCCEEDED);

        Payout cancellable = createPayout.create("config-key-3", PayoutsTestEnv.WALLET, 1_000L,
                "KES", PayoutsTestEnv.mpesaDestination(), null, Map.of(), null, null).payout();
        assertThat(cancelPayout.cancel("config-cancel-1", cancellable.id(), null).payout().state())
                .isEqualTo(PayoutState.CANCELLED);
        assertThat(riskDecisions.apply(
                createPayout.create("config-key-4", PayoutsTestEnv.WALLET, 1_000L, "KES",
                        PayoutsTestEnv.mpesaDestination(), null, Map.of(), null, null).payout().id(),
                "ALLOW", null).state()).isEqualTo(PayoutState.PENDING_RISK);
    }
}
