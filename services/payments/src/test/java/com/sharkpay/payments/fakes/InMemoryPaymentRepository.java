package com.sharkpay.payments.fakes;

import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.domain.StateTransition;
import com.sharkpay.payments.ports.PaymentRepository;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory payment repository (in-tree test fake, src/test, per ADR 003).
 * Mirrors the JPA adapter's semantics: save persists the snapshot and drains
 * the aggregate's pending transitions into the append-only log; listing
 * filters in memory over the id-ordered set and uses the last id as the
 * opaque cursor.
 */
public final class InMemoryPaymentRepository implements PaymentRepository {

    private final Map<String, PaymentIntent> intents = new ConcurrentHashMap<>();
    private final Map<String, List<StateTransition>> transitions = new ConcurrentHashMap<>();

    @Override
    public PaymentIntent save(PaymentIntent intent) {
        intents.put(intent.id(), intent);
        transitions.computeIfAbsent(intent.id(), key -> new ArrayList<>())
                .addAll(intent.drainPendingTransitions());
        return intent;
    }

    @Override
    public Optional<PaymentIntent> findById(String paymentId) {
        return Optional.ofNullable(intents.get(paymentId));
    }

    @Override
    public List<StateTransition> transitionsOf(String paymentId) {
        return transitions.getOrDefault(paymentId, List.of()).stream()
                .sorted(Comparator.comparingLong(StateTransition::seq))
                .toList();
    }

    @Override
    public Page list(PaymentFilter filter) {
        int limit = filter.effectiveLimit();
        List<PaymentIntent> matching = new ArrayList<>();
        intents.values().stream()
                .filter(intent -> matches(filter, intent))
                .sorted(Comparator.comparing(PaymentIntent::id))
                .filter(intent -> filter.cursor() == null
                        || intent.id().compareTo(filter.cursor()) > 0)
                .forEach(matching::add);
        boolean hasMore = matching.size() > limit;
        List<PaymentIntent> page = hasMore ? new ArrayList<>(matching.subList(0, limit)) : matching;
        String nextCursor = hasMore ? page.get(page.size() - 1).id() : null;
        return new Page(page, nextCursor);
    }

    private static boolean matches(PaymentFilter filter, PaymentIntent intent) {
        if (filter.state() != null && filter.state() != intent.state()) {
            return false;
        }
        if (filter.principalId() != null && !filter.principalId().equals(intent.principalId())) {
            return false;
        }
        if (filter.createdFrom() != null && intent.createdAt().isBefore(filter.createdFrom())) {
            return false;
        }
        return filter.createdTo() == null || intent.createdAt().isBefore(filter.createdTo());
    }

    /** Number of stored intents (all states). */
    public int count() {
        return intents.size();
    }

    /**
     * Drops the intent and its transition rows (simulates a lost / corrupted
     * row — the idempotency key still points at it).
     */
    public void remove(String paymentId) {
        intents.remove(paymentId);
        transitions.remove(paymentId);
    }

    /** Number of transition rows across all intents. */
    public int transitionCount() {
        return transitions.values().stream().mapToInt(List::size).sum();
    }

    /** A mutable copy of the raw store (test scaffolding). */
    public Map<String, PaymentIntent> snapshot() {
        return new LinkedHashMap<>(intents);
    }
}
