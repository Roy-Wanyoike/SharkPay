package com.sharkpay.payouts.config;

import com.sharkpay.payouts.domain.BackoffPolicy;
import com.sharkpay.payouts.domain.PayoutFeePolicy;
import com.sharkpay.payouts.ports.EventPublisher;
import com.sharkpay.payouts.ports.IdempotencyStore;
import com.sharkpay.payouts.ports.LedgerPort;
import com.sharkpay.payouts.ports.PayoutRepository;
import com.sharkpay.payouts.ports.PrincipalLookup;
import com.sharkpay.payouts.ports.ProviderGatewayPort;
import com.sharkpay.payouts.ports.Randomness;
import com.sharkpay.payouts.ports.SchedulerPort;
import com.sharkpay.payouts.ports.TransferRepository;
import com.sharkpay.payouts.ports.WalletHoldPort;
import com.sharkpay.payouts.service.CancelPayoutUseCase;
import com.sharkpay.payouts.service.CreatePayoutUseCase;
import com.sharkpay.payouts.service.CreateTransferUseCase;
import com.sharkpay.payouts.service.ExpirePayoutsUseCase;
import com.sharkpay.payouts.service.GetPayoutUseCase;
import com.sharkpay.payouts.service.HandleProviderResultUseCase;
import com.sharkpay.payouts.service.HandleRiskDecisionUseCase;
import com.sharkpay.payouts.service.PollPayoutsUseCase;
import com.sharkpay.payouts.service.ReleaseDuePayoutsUseCase;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;

/**
 * Production wiring of the hexagon, mirroring the wallet service's
 * {@code WalletConfig}: use-case beans depend only on ports.
 *
 * <p>Port adapters:</p>
 * <ul>
 *   <li>storage-backed ports ({@link PayoutRepository},
 *       {@link TransferRepository}, {@link IdempotencyStore}) — the JPA
 *       adapters in the storage package ({@code @Repository},
 *       component-scanned, Spring Data repositories against the
 *       Flyway-managed schema);</li>
 *   <li>{@link EventPublisher} — {@link LoggingEventPublisher} (structured
 *       logging) until the Kafka CloudEvent adapter lands;</li>
 *   <li>{@link SchedulerPort} — {@link LoggingSchedulerPort}: the polling
 *       sweeper is the release safety net in this wave; the Temporal
 *       workflow timer replaces it at integration (wiring point documented
 *       on the adapter);</li>
 *   <li>cross-service ports ({@link WalletHoldPort}, {@link PrincipalLookup},
 *       {@link LedgerPort}, {@link ProviderGatewayPort}) — fail-fast
 *       integration-pending placeholders until the REST adapters (wallet
 *       snapshot reads, identity principal lookup, Go ledger posting API,
 *       providers gateway) are wired by the integrator (ADR 003 §3).</li>
 * </ul>
 *
 * <p>Local tests never boot this context: they assemble the same use-cases
 * on the in-tree fakes ({@code com.sharkpay.payouts.fakes} in src/test).</p>
 */
@Configuration(proxyBeanMethods = false)
public class PayoutsConfig {

    @Bean
    public Clock clock() {
        return Clock.systemUTC();
    }

    @Bean
    public EventPublisher eventPublisher() {
        return new LoggingEventPublisher();
    }

    @Bean
    public SchedulerPort schedulerPort() {
        return new LoggingSchedulerPort();
    }

    @Bean
    public WalletHoldPort walletHoldPort() {
        return new IntegrationPendingWalletHoldPort();
    }

    @Bean
    public PrincipalLookup principalLookup() {
        return new IntegrationPendingPrincipalLookup();
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
    public Randomness randomness() {
        return new Randomness.SecureRandomness();
    }

    @Bean
    public PayoutFeePolicy payoutFeePolicy(
            @Value("${payouts.fees.mpesa-flat-minor:5500}") long mpesaFlat,
            @Value("${payouts.fees.mpesa-bps:100}") int mpesaBps,
            @Value("${payouts.fees.bank-flat-minor:3000}") long bankFlat,
            @Value("${payouts.fees.bank-bps:50}") int bankBps,
            @Value("${payouts.fees.on-chain-flat-minor:250000}") long onChainFlat,
            @Value("${payouts.fees.on-chain-bps:25}") int onChainBps) {
        return new PayoutFeePolicy(Map.of(
                com.sharkpay.payouts.domain.Rail.MPESA,
                new PayoutFeePolicy.RailFee(mpesaFlat, mpesaBps),
                com.sharkpay.payouts.domain.Rail.BANK,
                new PayoutFeePolicy.RailFee(bankFlat, bankBps),
                com.sharkpay.payouts.domain.Rail.ON_CHAIN,
                new PayoutFeePolicy.RailFee(onChainFlat, onChainBps)));
    }

    @Bean
    public BackoffPolicy backoffPolicy(
            @Value("${payouts.release.backoff-base-ms:1000}") long baseMs,
            @Value("${payouts.release.backoff-cap-ms:300000}") long capMs,
            @Value("${payouts.release.backoff-jitter-ms:250}") long jitterMs) {
        return new BackoffPolicy(Duration.ofMillis(baseMs), Duration.ofMillis(capMs),
                Duration.ofMillis(jitterMs));
    }

    @Bean
    public CreateTransferUseCase createTransferUseCase(WalletHoldPort wallets, LedgerPort ledger,
                                                        TransferRepository transfers,
                                                        IdempotencyStore idempotency,
                                                        EventPublisher events, Clock clock) {
        return new CreateTransferUseCase(wallets, ledger, transfers, idempotency, events, clock);
    }

    @Bean
    public CreatePayoutUseCase createPayoutUseCase(WalletHoldPort wallets,
                                                   PrincipalLookup principals, LedgerPort ledger,
                                                   PayoutRepository payouts,
                                                   IdempotencyStore idempotency,
                                                   EventPublisher events, SchedulerPort scheduler,
                                                   PayoutFeePolicy feePolicy, Clock clock) {
        return new CreatePayoutUseCase(wallets, principals, ledger, payouts, idempotency, events,
                scheduler, feePolicy, clock);
    }

    @Bean
    public GetPayoutUseCase getPayoutUseCase(PayoutRepository payouts) {
        return new GetPayoutUseCase(payouts);
    }

    @Bean
    public CancelPayoutUseCase cancelPayoutUseCase(PayoutRepository payouts, LedgerPort ledger,
                                                   IdempotencyStore idempotency,
                                                   SchedulerPort scheduler, Clock clock) {
        return new CancelPayoutUseCase(payouts, ledger, idempotency, scheduler, clock);
    }

    @Bean
    public HandleProviderResultUseCase handleProviderResultUseCase(PayoutRepository payouts,
                                                                   LedgerPort ledger,
                                                                   IdempotencyStore idempotency,
                                                                   EventPublisher events,
                                                                   Clock clock) {
        return new HandleProviderResultUseCase(payouts, ledger, idempotency, events, clock);
    }

    @Bean
    public HandleRiskDecisionUseCase handleRiskDecisionUseCase(PayoutRepository payouts,
                                                               LedgerPort ledger, Clock clock) {
        return new HandleRiskDecisionUseCase(payouts, ledger, clock);
    }

    @Bean
    public ReleaseDuePayoutsUseCase releaseDuePayoutsUseCase(PayoutRepository payouts,
                                                             ProviderGatewayPort gateway,
                                                             LedgerPort ledger,
                                                             EventPublisher events,
                                                             BackoffPolicy backoff,
                                                             Randomness randomness, Clock clock,
                                                             @Value("${payouts.release.batch-size:50}")
                                                             int batchSize,
                                                             @Value("${payouts.release.max-attempts:8}")
                                                             int maxAttempts) {
        return new ReleaseDuePayoutsUseCase(payouts, gateway, ledger, events, backoff, randomness,
                clock, batchSize, maxAttempts);
    }

    @Bean
    public ExpirePayoutsUseCase expirePayoutsUseCase(PayoutRepository payouts,
                                                     ProviderGatewayPort gateway,
                                                     LedgerPort ledger, Clock clock,
                                                     @Value("${payouts.expiry.batch-size:100}")
                                                     int batchSize) {
        return new ExpirePayoutsUseCase(payouts, gateway, ledger, clock, batchSize);
    }

    @Bean
    public PollPayoutsUseCase pollPayoutsUseCase(PayoutRepository payouts,
                                                 ProviderGatewayPort gateway,
                                                 HandleProviderResultUseCase results,
                                                 @Value("${payouts.poll.batch-size:100}")
                                                 int batchSize) {
        return new PollPayoutsUseCase(payouts, gateway, results, batchSize);
    }
}
