package com.sharkpay.payouts.testsupport;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sharkpay.payouts.api.GlobalExceptionHandler;
import com.sharkpay.payouts.api.InternalPayoutsController;
import com.sharkpay.payouts.api.PayoutController;
import com.sharkpay.payouts.api.TransferController;
import com.sharkpay.payouts.config.PayoutsConfig;
import com.sharkpay.payouts.domain.BackoffPolicy;
import com.sharkpay.payouts.domain.Destination;
import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.domain.PayoutFeePolicy;
import com.sharkpay.payouts.domain.PayoutState;
import com.sharkpay.payouts.domain.Transfer;
import com.sharkpay.payouts.fakes.FakeLedgerPort;
import com.sharkpay.payouts.fakes.FakePrincipalLookup;
import com.sharkpay.payouts.fakes.FakeProviderGateway;
import com.sharkpay.payouts.fakes.FakeWalletHoldPort;
import com.sharkpay.payouts.fakes.InMemoryIdempotencyStore;
import com.sharkpay.payouts.fakes.InMemoryPayoutRepository;
import com.sharkpay.payouts.fakes.InMemoryTransferRepository;
import com.sharkpay.payouts.fakes.MutableSchedulerPort;
import com.sharkpay.payouts.fakes.RecordingEventPublisher;
import com.sharkpay.payouts.fakes.ScriptedRandomness;
import com.sharkpay.payouts.service.CancelPayoutUseCase;
import com.sharkpay.payouts.service.CreatePayoutUseCase;
import com.sharkpay.payouts.service.CreateTransferUseCase;
import com.sharkpay.payouts.service.ExpirePayoutsUseCase;
import com.sharkpay.payouts.service.GetPayoutUseCase;
import com.sharkpay.payouts.service.HandleProviderResultUseCase;
import com.sharkpay.payouts.service.HandleRiskDecisionUseCase;
import com.sharkpay.payouts.service.PollPayoutsUseCase;
import com.sharkpay.payouts.service.PayoutSweeper;
import com.sharkpay.payouts.service.ReleaseDuePayoutsUseCase;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Assembles the full payouts hexagon on in-tree fakes with a mutable clock,
 * shared by domain/service/standalone-MockMvc tests (no Spring context, no
 * database, per ADR 003). The wiring mirrors {@link PayoutsConfig}
 * bean-for-bean; the fake ledger enforces the Go ledger's structural
 * invariants (≥ 2 legs, per-currency balance, key idempotency, reversal
 * pairing, wallet non-negativity) so money-safety assertions are real.
 */
public final class PayoutsTestEnv {

    public static final Instant START = Instant.parse("2026-09-01T10:00:00Z");
    /** A registered KES source wallet (matches ^wal_[0-9A-Za-z]{20,}$). */
    public static final String WALLET = "wal_0123456789abcdef0123456789abcdef";
    /** A second registered KES wallet (transfer destination). */
    public static final String OTHER_WALLET = "wal_fedcba9876543210fedcba9876543210";
    /** Opening balance of the default wallet (KES minor units). */
    public static final long DEFAULT_BALANCE = 10_000_000L;

    public final MutableClock clock;
    public final ScriptedRandomness randomness;
    public final FakeWalletHoldPort wallets;
    public final FakePrincipalLookup principals;
    public final FakeLedgerPort ledger;
    public final FakeProviderGateway gateway;
    public final InMemoryPayoutRepository payouts;
    public final InMemoryTransferRepository transfers;
    public final InMemoryIdempotencyStore idempotency;
    public final RecordingEventPublisher events;
    public final MutableSchedulerPort scheduler;
    public final PayoutFeePolicy feePolicy;
    public final BackoffPolicy backoff;

    public final UUID principalId;
    public final UUID walletAccount;
    public final UUID otherWalletAccount;

    public final CreateTransferUseCase createTransfer;
    public final CreatePayoutUseCase createPayout;
    public final GetPayoutUseCase getPayout;
    public final CancelPayoutUseCase cancelPayout;
    public final HandleProviderResultUseCase providerResults;
    public final HandleRiskDecisionUseCase riskDecisions;
    public final ReleaseDuePayoutsUseCase releaseDue;
    public final ExpirePayoutsUseCase expireOverdue;
    public final PollPayoutsUseCase pollInFlight;
    public final PayoutSweeper sweeper;

    public final TransferController transferController;
    public final PayoutController payoutController;
    public final InternalPayoutsController internalController;
    public final GlobalExceptionHandler errorHandler;

    public PayoutsTestEnv() {
        this(START, 50, 8, 100, 100);
    }

    public PayoutsTestEnv(int releaseBatchSize, int maxAttempts) {
        this(START, releaseBatchSize, maxAttempts, 100, 100);
    }

    public PayoutsTestEnv(Instant start, int releaseBatchSize, int maxAttempts,
                          int expiryBatchSize, int pollBatchSize) {
        clock = new MutableClock(start);
        randomness = new ScriptedRandomness();
        wallets = new FakeWalletHoldPort();
        principals = new FakePrincipalLookup();
        ledger = new FakeLedgerPort();
        gateway = new FakeProviderGateway();
        payouts = new InMemoryPayoutRepository();
        transfers = new InMemoryTransferRepository();
        idempotency = new InMemoryIdempotencyStore();
        events = new RecordingEventPublisher();
        scheduler = new MutableSchedulerPort();
        feePolicy = PayoutFeePolicy.defaults();
        backoff = new BackoffPolicy(Duration.ofSeconds(1), Duration.ofMinutes(5), Duration.ZERO);

        principalId = UUID.randomUUID();
        walletAccount = UUID.randomUUID();
        otherWalletAccount = UUID.randomUUID();
        principals.addActiveLimited(principalId);
        registerWallet(WALLET, principalId, "KES", DEFAULT_BALANCE, walletAccount);
        registerWallet(OTHER_WALLET, UUID.randomUUID(), "KES", 0L, otherWalletAccount);

        createTransfer = new CreateTransferUseCase(wallets, ledger, transfers, idempotency,
                events, clock);
        createPayout = new CreatePayoutUseCase(wallets, principals, ledger, payouts, idempotency,
                events, scheduler, feePolicy, clock);
        getPayout = new GetPayoutUseCase(payouts);
        cancelPayout = new CancelPayoutUseCase(payouts, ledger, idempotency, scheduler, clock);
        providerResults = new HandleProviderResultUseCase(payouts, ledger, idempotency, events,
                clock);
        riskDecisions = new HandleRiskDecisionUseCase(payouts, ledger, clock);
        releaseDue = new ReleaseDuePayoutsUseCase(payouts, gateway, ledger, events, backoff,
                randomness, clock, releaseBatchSize, maxAttempts);
        expireOverdue = new ExpirePayoutsUseCase(payouts, gateway, ledger, clock, expiryBatchSize);
        pollInFlight = new PollPayoutsUseCase(payouts, gateway, providerResults, pollBatchSize);
        sweeper = new PayoutSweeper(releaseDue, expireOverdue, pollInFlight);

        transferController = new TransferController(createTransfer);
        payoutController = new PayoutController(createPayout, getPayout, cancelPayout);
        internalController = new InternalPayoutsController(providerResults, riskDecisions, sweeper);
        errorHandler = new GlobalExceptionHandler();
    }

    /** Registers a wallet on the read port AND seeds the ledger authority. */
    public void registerWallet(String walletId, UUID ownerPrincipal, String currency,
                               long availableMinor, UUID ledgerAccountId) {
        wallets.addWallet(walletId, ownerPrincipal, currency, availableMinor, ledgerAccountId);
        if (availableMinor > 0) {
            ledger.seed(ledgerAccountId.toString(),
                    com.sharkpay.money.Money.of(availableMinor, currency));
        }
    }

    /** A valid M-Pesa destination (contracts/openapi/v1/payouts.yaml example). */
    public static Destination mpesaDestination() {
        return new Destination("mpesa", "+254712345678", null, null, null, null, null, null);
    }

    /** A valid bank destination. */
    public static Destination bankDestination() {
        return new Destination("bank", null, "12345", "ACC-991", "Jane Doe", "KE", null, null);
    }

    /** A valid on-chain destination (USDC on Base). */
    public static Destination onChainDestination() {
        return new Destination("on_chain", null, null, null, null, null, "base",
                "0x8f6c3b1e9d2a4f7b8e5c6a1b2c3d4e5f6a7b8c9d");
    }

    /** Creates an accepted KES 500 000 M-Pesa payout for the default wallet. */
    public Payout createDefaultPayout() {
        return createPayout("key-1");
    }

    /** Creates an accepted KES 500 000 M-Pesa payout for the default wallet. */
    public Payout createPayout(String idempotencyKey) {
        return createPayout.create(idempotencyKey, WALLET, 500_000L, "KES", mpesaDestination(),
                null, Map.of(), null, null).payout();
    }

    /** Creates a KES transfer of {@code amountMinor} from the default wallet. */
    public Transfer createTransfer(String idempotencyKey, long amountMinor) {
        return createTransfer.create(idempotencyKey, WALLET, OTHER_WALLET, amountMinor, "KES",
                Map.of()).transfer();
    }

    /**
     * A PROCESSING payout assembled directly with the given composite
     * provider ref (no ledger side effects) — provider-ref parsing fixture.
     */
    public Payout fixturePayout(String providerRef) {
        Payout payout = new Payout("pot_0123456789abcdef0123456789abcdef",
                UUID.randomUUID(), WALLET, walletAccount,
                com.sharkpay.money.Money.of(500_000, "KES"),
                com.sharkpay.money.Money.of(10_500, "KES"),
                com.sharkpay.money.Money.of(5_500, "KES"),
                com.sharkpay.payouts.domain.Rail.MPESA, mpesaDestination(),
                PayoutState.PROCESSING, providerRef, null, null, 0, null, null,
                START.plusSeconds(900), UUID.randomUUID(), null, null, Map.of(), START, START,
                List.of());
        payouts.save(payout);
        return payout;
    }

    /**
     * Standalone MockMvc with a Jackson 3 (tools.jackson) JSON mapper:
     * ISO-8601 instants (Jackson 3 default) and NON_NULL inclusion — optional
     * fields (metadata, failure_reason, return_reason, provider_ref) are
     * omitted, matching payouts.yaml/transfers.yaml additionalProperties:
     * false. Boot 4 = Jackson 3: no com.fasterxml.jackson.databind anywhere.
     */
    public MockMvc mockMvc() {
        JsonMapper mapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(value ->
                        value.withValueInclusion(JsonInclude.Include.NON_NULL))
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(transferController, payoutController,
                        internalController)
                .setControllerAdvice(errorHandler)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .setValidator(validator)
                .build();
    }
}
