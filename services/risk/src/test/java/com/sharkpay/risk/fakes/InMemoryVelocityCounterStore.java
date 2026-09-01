package com.sharkpay.risk.fakes;

import com.sharkpay.money.Money;
import com.sharkpay.risk.ports.VelocityCounterStore;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * In-memory windowed counter store (ADR 003: consumer-driven port + fake).
 * Sliding window [now - window, now] inclusive; the store's clock is the
 * MutableClock shared with the use-cases under test.
 */
public final class InMemoryVelocityCounterStore implements VelocityCounterStore {

    public record Recorded(String subject, Money amount, Instant at) {
    }

    private final java.time.Clock clock;
    private final List<Recorded> recorded = new ArrayList<>();

    public InMemoryVelocityCounterStore(java.time.Clock clock) {
        this.clock = clock;
    }

    @Override
    public int countInWindow(String subject, Duration window) {
        Instant from = clock.instant().minus(window);
        return (int) recorded.stream()
                .filter(entry -> entry.subject().equals(subject) && !entry.at().isBefore(from))
                .count();
    }

    @Override
    public Money amountInWindow(String subject, String currency, Duration window) {
        Instant from = clock.instant().minus(window);
        Money total = Money.zero(currency);
        for (Recorded entry : recorded) {
            if (entry.subject().equals(subject)
                    && entry.amount().currency().equals(currency)
                    && !entry.at().isBefore(from)) {
                total = total.add(entry.amount());
            }
        }
        return total;
    }

    @Override
    public void record(String subject, Money amount, Instant occurredAt) {
        recorded.add(new Recorded(subject, amount, occurredAt));
    }

    public List<Recorded> entries() {
        return List.copyOf(recorded);
    }
}
