package com.sharkpay.wallet.config;

import com.sharkpay.wallet.ports.EventPublisher;
import com.sharkpay.wallet.ports.HoldRepository;
import com.sharkpay.wallet.ports.IdempotencyStore;
import com.sharkpay.wallet.ports.LedgerAccounts;
import com.sharkpay.wallet.ports.PrincipalLookup;
import com.sharkpay.wallet.ports.ProjectionStore;
import com.sharkpay.wallet.ports.WalletRepository;
import com.sharkpay.wallet.service.ApplyLedgerEventUseCase;
import com.sharkpay.wallet.service.BalanceReader;
import com.sharkpay.wallet.service.CaptureHoldUseCase;
import com.sharkpay.wallet.service.ChangeWalletStatusUseCase;
import com.sharkpay.wallet.service.CreateWalletUseCase;
import com.sharkpay.wallet.service.GetStatementUseCase;
import com.sharkpay.wallet.service.GetWalletUseCase;
import com.sharkpay.wallet.service.ListWalletsUseCase;
import com.sharkpay.wallet.service.PlaceHoldUseCase;
import com.sharkpay.wallet.service.ReleaseHoldUseCase;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;

/**
 * Production wiring of the hexagon, mirroring the identity service's
 * {@code IdentityConfig}: use-case beans depend only on ports.
 *
 * <p>Port adapters:</p>
 * <ul>
 *   <li>storage-backed ports ({@link WalletRepository}, {@link HoldRepository},
 *       {@link IdempotencyStore}, {@link ProjectionStore}) — the JPA adapters
 *       in the storage package ({@code @Repository}, component-scanned, JPA
 *       repositories against the Flyway-managed schema);</li>
 *   <li>{@link EventPublisher} — {@link LoggingEventPublisher} (structured
 *       logging) until the NATS/Kafka CloudEvent adapter lands;</li>
 *   <li>cross-service ports ({@link PrincipalLookup}, {@link LedgerAccounts})
 *       — fail-fast integration-pending placeholders until the REST adapters
 *       (identity principal lookup, Go ledger account provisioning) are wired
 *       by the integrator (ADR 003 §3). The dev HTTP ledger-event intake
 *       ({@code POST /internal/ledger-events}) feeds the projection until the
 *       NATS/Kafka {@code ledger.posting.committed.v1} binding lands.</li>
 * </ul>
 *
 * <p>Local tests never boot this context: they assemble the same use-cases on
 * the in-tree fakes ({@code com.sharkpay.wallet.fakes} in src/test).</p>
 */
@Configuration(proxyBeanMethods = false)
public class WalletConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public EventPublisher eventPublisher() {
        return new LoggingEventPublisher();
    }

    @Bean
    public PrincipalLookup principalLookup() {
        return new IntegrationPendingPrincipalLookup();
    }

    @Bean
    public LedgerAccounts ledgerAccounts() {
        return new IntegrationPendingLedgerAccounts();
    }

    @Bean
    public BalanceReader balanceReader(ProjectionStore projections, HoldRepository holds) {
        return new BalanceReader(projections, holds);
    }

    @Bean
    public CreateWalletUseCase createWalletUseCase(WalletRepository wallets, PrincipalLookup principals,
                                                    LedgerAccounts ledgerAccounts,
                                                    IdempotencyStore idempotency,
                                                    EventPublisher events, Clock clock) {
        return new CreateWalletUseCase(wallets, principals, ledgerAccounts, idempotency, events, clock);
    }

    @Bean
    public ChangeWalletStatusUseCase changeWalletStatusUseCase(WalletRepository wallets,
                                                                EventPublisher events, Clock clock) {
        return new ChangeWalletStatusUseCase(wallets, events, clock);
    }

    @Bean
    public PlaceHoldUseCase placeHoldUseCase(WalletRepository wallets, HoldRepository holds,
                                              BalanceReader balances, IdempotencyStore idempotency,
                                              EventPublisher events, Clock clock) {
        return new PlaceHoldUseCase(wallets, holds, balances, idempotency, events, clock);
    }

    @Bean
    public ReleaseHoldUseCase releaseHoldUseCase(HoldRepository holds, WalletRepository wallets,
                                                  BalanceReader balances, IdempotencyStore idempotency,
                                                  EventPublisher events, Clock clock) {
        return new ReleaseHoldUseCase(holds, wallets, balances, idempotency, events, clock);
    }

    @Bean
    public CaptureHoldUseCase captureHoldUseCase(HoldRepository holds, WalletRepository wallets,
                                                  BalanceReader balances, IdempotencyStore idempotency,
                                                  EventPublisher events, Clock clock) {
        return new CaptureHoldUseCase(holds, wallets, balances, idempotency, events, clock);
    }

    @Bean
    public GetWalletUseCase getWalletUseCase(WalletRepository wallets, BalanceReader balances) {
        return new GetWalletUseCase(wallets, balances);
    }

    @Bean
    public ListWalletsUseCase listWalletsUseCase(WalletRepository wallets, BalanceReader balances) {
        return new ListWalletsUseCase(wallets, balances);
    }

    @Bean
    public GetStatementUseCase getStatementUseCase(WalletRepository wallets, ProjectionStore projections) {
        return new GetStatementUseCase(wallets, projections);
    }

    @Bean
    public ApplyLedgerEventUseCase applyLedgerEventUseCase(WalletRepository wallets,
                                                            ProjectionStore projections,
                                                            BalanceReader balances,
                                                            EventPublisher events) {
        return new ApplyLedgerEventUseCase(wallets, projections, balances, events);
    }
}
