package com.sharkpay.wallet.config;

import com.sharkpay.wallet.fakes.FakePrincipalLookup;
import com.sharkpay.wallet.ports.EventPublisher;
import com.sharkpay.wallet.ports.IdempotencyStore;
import com.sharkpay.wallet.ports.LedgerAccounts;
import com.sharkpay.wallet.ports.LedgerEventConsumer;
import com.sharkpay.wallet.ports.PrincipalLookup;
import com.sharkpay.wallet.ports.WalletRepository;
import com.sharkpay.wallet.service.CaptureHoldUseCase;
import com.sharkpay.wallet.service.CreateWalletUseCase;
import com.sharkpay.wallet.service.PlaceHoldUseCase;
import com.sharkpay.wallet.service.ReleaseHoldUseCase;
import com.sharkpay.wallet.testsupport.WalletTestEnv;
import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Exercises the production {@link WalletConfig} bean factories without a
 * Spring context: every factory must build a usable object, the cross-service
 * port placeholders fail fast and loud (money path honesty, ADR 003 §3), and
 * the storage-backed port beans are satisfied by component-scanned JPA
 * adapters at runtime (covered by JpaAdaptersTest). Use-case behavior is
 * proven on the test-tree fakes, which mirror those adapters.
 */
class WalletConfigTest {

    private final WalletTestEnv env = new WalletTestEnv();
    private final WalletConfig config = new WalletConfig();

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
    void principalLookupPlaceholderFailsFastAndLoud() {
        PrincipalLookup lookup = config.principalLookup();
        assertThat(lookup).isInstanceOf(IntegrationPendingPrincipalLookup.class);
        UUID principalId = UUID.randomUUID();
        assertThatThrownBy(() -> lookup.findById(principalId))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("PrincipalLookup adapter is not wired yet")
                .hasMessageContaining(principalId.toString());
    }

    @Test
    void ledgerAccountsPlaceholderFailsFastAndLoud() {
        LedgerAccounts accounts = config.ledgerAccounts();
        assertThat(accounts).isInstanceOf(IntegrationPendingLedgerAccounts.class);
        UUID principalId = UUID.randomUUID();
        assertThatThrownBy(() -> accounts.provisionWalletAccount(principalId, "KES"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("LedgerAccounts adapter is not wired yet")
                .hasMessageContaining(principalId.toString());
    }

    @Test
    void allUseCaseBeanMethodsBuildWorkingObjects() {
        // the storage-backed ports, satisfied here by their in-tree mirrors
        WalletRepository wallets = env.wallets;
        EventPublisher events = env.events;
        IdempotencyStore idempotency = env.idempotency;
        Clock clock = env.clock;

        CreateWalletUseCase createWallet = config.createWalletUseCase(wallets,
                env.principals, env.ledgerAccounts, idempotency, events, clock);
        PlaceHoldUseCase placeHold = config.placeHoldUseCase(wallets, env.holds,
                env.balanceReader, idempotency, events, clock);
        ReleaseHoldUseCase releaseHold = config.releaseHoldUseCase(env.holds, wallets,
                env.balanceReader, idempotency, events, clock);
        CaptureHoldUseCase captureHold = config.captureHoldUseCase(env.holds, wallets,
                env.balanceReader, idempotency, events, clock);
        config.changeWalletStatusUseCase(wallets, events, clock);
        config.getWalletUseCase(wallets, env.balanceReader);
        config.listWalletsUseCase(wallets, env.balanceReader);
        config.getStatementUseCase(wallets, env.projections);
        LedgerEventConsumer projector = config.applyLedgerEventUseCase(wallets, env.projections,
                env.balanceReader, events);

        // smoke: the whole wiring works end to end (same money-safety path as
        // the service tests: credit → hold → partial capture → release → debit)
        UUID principal = env.newPrincipal();
        CreateWalletUseCase.Result wallet = createWallet.create("key-1", principal, "KES");
        env.credit(wallet.wallet(), 1_000);
        PlaceHoldUseCase.Result hold = placeHold.place("hold-1", wallet.wallet().id(), 400,
                "KES", com.sharkpay.wallet.domain.Source.PAYMENTS, UUID.randomUUID(), null);
        captureHold.capture("cap-1", hold.hold().id(), 100L, null);
        releaseHold.release("rel-1",
                placeHold.place("hold-2", wallet.wallet().id(), 100, "KES",
                        com.sharkpay.wallet.domain.Source.PAYMENTS, UUID.randomUUID(), null)
                        .hold().id(), null);
        env.feed.deliver(env.feed.entry(env.accountOf(wallet.wallet()), "KES",
                com.sharkpay.wallet.domain.Direction.DEBIT, 40,
                com.sharkpay.wallet.domain.Source.PAYOUTS, UUID.randomUUID(), "hold",
                Instant.parse("2026-09-01T10:00:06Z")));

        assertThat(projector).isNotNull();
        assertThat(env.balanceReader.balancesOf(wallet.wallet()).total().amountMinor())
                .isEqualTo(960);
        assertThat(env.principals.findById(principal)).isPresent();
    }

    @Test
    void configSmokeReflectsTheFakePortsAsTheJpaAdaptersWould() {
        // the config never references the fakes: its port parameters are the
        // port interfaces themselves (satisfied by JPA adapters at runtime).
        // Wiring the fake instances through the same factories proves the
        // factory signatures accept any port implementation.
        FakePrincipalLookup fakePrincipals = new FakePrincipalLookup();
        com.sharkpay.wallet.fakes.FakeLedgerAccounts fakeAccounts =
                new com.sharkpay.wallet.fakes.FakeLedgerAccounts();
        CreateWalletUseCase createWallet = config.createWalletUseCase(env.wallets, fakePrincipals,
                fakeAccounts, env.idempotency, env.events, env.clock);
        UUID principal = fakePrincipals.register(UUID.randomUUID()).id();
        assertThat(createWallet.create("cfg-1", principal, "USD").wallet().currency())
                .isEqualTo("USD");
    }
}
