package com.sharkpay.wallet.testsupport;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sharkpay.wallet.api.GlobalExceptionHandler;
import com.sharkpay.wallet.api.InternalHoldController;
import com.sharkpay.wallet.api.InternalWalletController;
import com.sharkpay.wallet.api.LedgerEventsController;
import com.sharkpay.wallet.api.WalletController;
import com.sharkpay.wallet.domain.Direction;
import com.sharkpay.wallet.domain.Source;
import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.fakes.FakeLedgerAccounts;
import com.sharkpay.wallet.fakes.FakeLedgerFeed;
import com.sharkpay.wallet.fakes.FakePrincipalLookup;
import com.sharkpay.wallet.fakes.InMemoryHoldRepository;
import com.sharkpay.wallet.fakes.InMemoryIdempotencyStore;
import com.sharkpay.wallet.fakes.InMemoryProjectionStore;
import com.sharkpay.wallet.fakes.InMemoryWalletRepository;
import com.sharkpay.wallet.fakes.RecordingEventPublisher;
import com.sharkpay.wallet.ledger.LedgerPostingEvent;
import com.sharkpay.wallet.ports.LedgerEventConsumer;
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
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.UUID;

/**
 * Assembles the full wallet object graph on in-memory fakes with a mutable
 * clock, shared by service and standalone-MockMvc controller tests
 * (no Spring context, per ADR 003 — no @SpringBootTest, no database).
 */
public final class WalletTestEnv {

    public static final Instant START = Instant.parse("2026-09-01T10:00:00Z");

    public final MutableClock clock;
    public final FakePrincipalLookup principals;
    public final FakeLedgerAccounts ledgerAccounts;
    public final InMemoryWalletRepository wallets;
    public final InMemoryHoldRepository holds;
    public final InMemoryProjectionStore projections;
    public final InMemoryIdempotencyStore idempotency;
    public final RecordingEventPublisher events;
    public final BalanceReader balanceReader;
    public final FakeLedgerFeed feed;

    public final CreateWalletUseCase createWallet;
    public final ChangeWalletStatusUseCase changeStatus;
    public final PlaceHoldUseCase placeHold;
    public final ReleaseHoldUseCase releaseHold;
    public final CaptureHoldUseCase captureHold;
    public final GetWalletUseCase getWallet;
    public final ListWalletsUseCase listWallets;
    public final GetStatementUseCase statement;
    public final LedgerEventConsumer ledgerConsumer;

    public final WalletController walletController;
    public final InternalWalletController internalWalletController;
    public final InternalHoldController internalHoldController;
    public final LedgerEventsController ledgerEventsController;
    public final GlobalExceptionHandler errorHandler;

    public WalletTestEnv() {
        this(START);
    }

    public WalletTestEnv(Instant start) {
        clock = new MutableClock(start);
        principals = new FakePrincipalLookup();
        ledgerAccounts = new FakeLedgerAccounts();
        wallets = new InMemoryWalletRepository();
        holds = new InMemoryHoldRepository();
        projections = new InMemoryProjectionStore();
        idempotency = new InMemoryIdempotencyStore();
        events = new RecordingEventPublisher();
        balanceReader = new BalanceReader(projections, holds);

        createWallet = new CreateWalletUseCase(wallets, principals, ledgerAccounts, idempotency,
                events, clock);
        changeStatus = new ChangeWalletStatusUseCase(wallets, events, clock);
        placeHold = new PlaceHoldUseCase(wallets, holds, balanceReader, idempotency, events, clock);
        releaseHold = new ReleaseHoldUseCase(holds, wallets, balanceReader, idempotency, events, clock);
        captureHold = new CaptureHoldUseCase(holds, wallets, balanceReader, idempotency, events, clock);
        getWallet = new GetWalletUseCase(wallets, balanceReader);
        listWallets = new ListWalletsUseCase(wallets, balanceReader);
        statement = new GetStatementUseCase(wallets, projections);
        ledgerConsumer = new ApplyLedgerEventUseCase(wallets, projections, balanceReader, events);
        feed = new FakeLedgerFeed(ledgerConsumer);

        walletController = new WalletController(getWallet, listWallets, statement);
        internalWalletController = new InternalWalletController(createWallet, changeStatus,
                placeHold, getWallet);
        internalHoldController = new InternalHoldController(releaseHold, captureHold, holds);
        ledgerEventsController = new LedgerEventsController(
                (ApplyLedgerEventUseCase) ledgerConsumer, projections);
        errorHandler = new GlobalExceptionHandler();
    }

    /** Registers a fresh ACTIVE principal and returns its id. */
    public UUID newPrincipal() {
        UUID principalId = UUID.randomUUID();
        principals.register(principalId);
        return principalId;
    }

    /** Creates a wallet for a fresh ACTIVE principal. */
    public Wallet newWallet(String currency) {
        return createWallet.create("env-" + UUID.randomUUID(), newPrincipal(), currency).wallet();
    }

    /** The ledger account id a wallet's legs are keyed by. */
    public UUID accountOf(Wallet wallet) {
        return FakeLedgerAccounts.accountId(wallet.principalId(), wallet.currency());
    }

    /** Commits a ledger credit to the wallet's account (money in). */
    public LedgerPostingEvent credit(Wallet wallet, long amountMinor) {
        return feed.commit(accountOf(wallet), wallet.currency(), Direction.CREDIT, amountMinor,
                Source.PAYMENTS, UUID.randomUUID(), "capture");
    }

    /** Commits a ledger debit to the wallet's account (money out). */
    public LedgerPostingEvent debit(Wallet wallet, long amountMinor) {
        return feed.commit(accountOf(wallet), wallet.currency(), Direction.DEBIT, amountMinor,
                Source.PAYOUTS, UUID.randomUUID(), "hold");
    }

    /**
     * Standalone MockMvc with a Jackson 3 (tools.jackson) JSON mapper: contract
     * ISO-8601 instants (Jackson 3 default) and NON_NULL inclusion — optional
     * fields (reason, from_status, closed_at) are omitted, matching the event
     * schemas' additionalProperties: false + typed fields. Boot 4 = Jackson 3:
     * no com.fasterxml.jackson.databind anywhere.
     */
    public MockMvc mockMvc() {
        JsonMapper mapper = JsonMapper.builder()
                .changeDefaultPropertyInclusion(value ->
                        value.withValueInclusion(JsonInclude.Include.NON_NULL))
                .build();
        LocalValidatorFactoryBean validator = new LocalValidatorFactoryBean();
        validator.afterPropertiesSet();
        return MockMvcBuilders.standaloneSetup(walletController, internalWalletController,
                        internalHoldController, ledgerEventsController)
                .setControllerAdvice(errorHandler)
                .setMessageConverters(new JacksonJsonHttpMessageConverter(mapper))
                .setValidator(validator)
                .build();
    }
}
