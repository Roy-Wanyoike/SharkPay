package com.sharkpay.gateway.service;

import com.sharkpay.gateway.events.CloudEventEnvelope;
import com.sharkpay.gateway.events.EnvelopeCodec;
import com.sharkpay.gateway.events.EventIds;
import com.sharkpay.gateway.events.EventTypeCatalog;
import com.sharkpay.gateway.ports.EventConsumer;
import com.sharkpay.gateway.ports.Randomness;

import java.time.Clock;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The sandbox simulated provider — clearly separated from the /v1 surface
 * under {@code /sandbox/*}: a deterministic, scripted accept-then-settle
 * flow. Creating a sandbox payment dispatches {@code payment.created};
 * each poll advances the script exactly one step
 * (CREATED → PENDING_PROVIDER → SUCCEEDED, dispatching the matching
 * event), so merchants can exercise their webhook receivers end-to-end
 * with zero money movement. State is in-memory and ephemeral by design
 * (sandbox data is never persisted, never reconciled).
 */
public final class SandboxPaymentService {

    /** Sandbox event source — clearly separated from the real producers. */
    public static final String SOURCE = "sharkpay/sandbox";

    private static final Map<String, Integer> EXPONENTS =
            Map.of("KES", 2, "USD", 2, "EUR", 2, "GBP", 2, "USDC", 6, "USDT", 6);

    private static final String WALLET_PATTERN = "^wal_[0-9A-Za-z]{20,}$";
    private static final java.util.Set<String> RAILS =
            java.util.Set.of("honeycoin", "mpesa", "bank", "on_chain");

    private final Map<String, SandboxPayment> payments = new ConcurrentHashMap<>();
    private final Randomness randomness;
    private final EnvelopeCodec codec;
    private final EventConsumer dispatcher;
    private final Clock clock;

    public SandboxPaymentService(Randomness randomness, EnvelopeCodec codec,
                                 EventConsumer dispatcher, Clock clock) {
        this.randomness = Objects.requireNonNull(randomness, "randomness is required");
        this.codec = Objects.requireNonNull(codec, "codec is required");
        this.dispatcher = Objects.requireNonNull(dispatcher, "dispatcher is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /** Creates a scripted payment (state CREATED, payment.created dispatched). */
    public SandboxPayment create(long amountMinor, String currency, String destinationWallet,
                                 String rail) {
        Objects.requireNonNull(currency, "currency is required");
        Objects.requireNonNull(destinationWallet, "destinationWallet is required");
        Objects.requireNonNull(rail, "rail is required");
        String normalized = currency.toUpperCase(java.util.Locale.ROOT);
        Integer exponent = EXPONENTS.get(normalized);
        if (exponent == null) {
            throw new IllegalArgumentException("unsupported sandbox currency: " + currency);
        }
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amount_minor must be a positive integer");
        }
        if (!destinationWallet.matches(WALLET_PATTERN)) {
            throw new IllegalArgumentException("destination_wallet must match " + WALLET_PATTERN);
        }
        if (!RAILS.contains(rail)) {
            throw new IllegalArgumentException("rail must be one of " + RAILS);
        }
        SandboxPayment payment = new SandboxPayment(randomness.sandboxPaymentId(), "CREATED",
                amountMinor, normalized, exponent, destinationWallet, rail, clock.instant());
        payments.put(payment.id(), payment);
        dispatch(payment, EventTypeCatalog.PAYMENT_CREATED);
        return payment;
    }

    /**
     * Polls the scripted payment: each poll advances the script exactly one
     * step (CREATED → PENDING_PROVIDER → SUCCEEDED) and dispatches the
     * matching event; SUCCEEDED polls are stable.
     */
    public SandboxPayment get(String id) {
        SandboxPayment payment = payments.get(id);
        if (payment == null) {
            throw new NoSuchElementException("sandbox payment " + id + " not found");
        }
        return switch (payment.state()) {
            case "CREATED" -> advance(payment, "PENDING_PROVIDER",
                    EventTypeCatalog.PAYMENT_PENDING_PROVIDER);
            case "PENDING_PROVIDER" -> advance(payment, "SUCCEEDED",
                    EventTypeCatalog.PAYMENT_SUCCEEDED);
            default -> payment;
        };
    }

    private SandboxPayment advance(SandboxPayment payment, String nextState,
                                   EventTypeCatalog event) {
        SandboxPayment advanced = new SandboxPayment(payment.id(), nextState,
                payment.amountMinor(), payment.currency(), payment.exponent(),
                payment.destinationWallet(), payment.rail(), payment.createdAt());
        payments.put(advanced.id(), advanced);
        dispatch(advanced, event);
        return advanced;
    }

    private void dispatch(SandboxPayment payment, EventTypeCatalog event) {
        var data = codec.newDataObject();
        data.put("payment_id", payment.id());
        data.put("state", payment.state());
        data.set("amount", money(payment.amountMinor(), payment.currency(), payment.exponent()));
        data.set("fee", money(0, payment.currency(), payment.exponent()));
        data.put("destination_wallet", payment.destinationWallet());
        data.put("rail", payment.rail());
        CloudEventEnvelope envelope = CloudEventEnvelope.of(EventIds.uuidV7(), event.topic(),
                SOURCE, payment.id(), clock.instant(), data);
        dispatcher.onEvent(envelope);
    }

    private tools.jackson.databind.node.ObjectNode money(long amountMinor, String currency,
                                                         int exponent) {
        var money = codec.newDataObject();
        money.put("amount_minor", amountMinor);
        money.put("currency", currency);
        money.put("exponent", exponent);
        return money;
    }

    /**
     * @param id                {@code pay_...} sandbox id
     * @param state             CREATED | PENDING_PROVIDER | SUCCEEDED
     * @param amountMinor       integer minor units
     * @param currency          one of the six V1 currencies
     * @param exponent          minor-unit exponent of the currency
     * @param destinationWallet {@code wal_...}
     * @param rail              honeycoin | mpesa | bank | on_chain
     * @param createdAt         creation instant
     */
    public record SandboxPayment(String id, String state, long amountMinor, String currency,
                                 int exponent, String destinationWallet, String rail,
                                 java.time.Instant createdAt) {
    }
}
