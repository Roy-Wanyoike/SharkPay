package com.sharkpay.payments.storage;

import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.domain.StateTransition;
import com.sharkpay.payments.ports.PaymentRepository;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Repository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * JPA adapter for the payment repository port: entity mapping + delegation
 * only (the domain owns every rule). Saving persists the aggregate snapshot
 * AND drains the aggregate's pending transitions into the append-only
 * {@code payment_state_transitions} log (every state change is a row —
 * STATE-MACHINES.md §7.3). Caller metadata is stored as a JSON document in
 * {@code metadata_json} (tools.jackson — never com.fasterxml databind).
 *
 * <p>Listing (payments.yaml listPayments) filters in memory over the
 * id-ordered snapshot set and uses the last id as the opaque cursor — the
 * wallet service's V1 stance; a query-planned implementation is an ops-time
 * optimization behind the same port contract.</p>
 */
@Repository
public final class JpaPaymentRepository implements PaymentRepository {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final TypeReference<LinkedHashMap<String, String>> METADATA_TYPE =
            new TypeReference<>() {
            };

    private final PaymentIntentJpaRepository intents;
    private final PaymentStateTransitionJpaRepository transitions;

    public JpaPaymentRepository(PaymentIntentJpaRepository intents,
                                PaymentStateTransitionJpaRepository transitions) {
        this.intents = intents;
        this.transitions = transitions;
    }

    @Override
    public PaymentIntent save(PaymentIntent intent) {
        PaymentIntentEntity entity = intents.findById(intent.id())
                .map(existing -> {
                    existing.applyDomain(intent);
                    return existing;
                })
                .orElseGet(() -> PaymentIntentEntity.fromDomain(intent, encodeMetadata(intent)));
        PaymentIntentEntity saved = intents.save(entity);
        for (StateTransition transition : intent.drainPendingTransitions()) {
            transitions.save(new PaymentStateTransitionEntity(transition.paymentId(),
                    transition.seq(), PaymentStateTransitionEntity.wireOf(transition.from()),
                    PaymentStateTransitionEntity.wireOf(transition.to()), transition.reason(),
                    transition.entryId(), transition.occurredAt()));
        }
        return saved.toDomain(decodeMetadata(saved));
    }

    @Override
    public Optional<PaymentIntent> findById(String paymentId) {
        return intents.findById(paymentId).map(entity -> entity.toDomain(decodeMetadata(entity)));
    }

    @Override
    public List<StateTransition> transitionsOf(String paymentId) {
        return transitions.findByPaymentIdOrderBySeqAsc(paymentId).stream()
                .map(entity -> new StateTransition(entity.getPaymentId(), entity.getSeq(),
                        entity.getFromState() == null ? null
                                : PaymentState.fromWire(entity.getFromState()),
                        PaymentState.fromWire(entity.getToState()), entity.getReason(),
                        entity.getEntryId(), entity.getOccurredAt()))
                .toList();
    }

    @Override
    public Page list(PaymentFilter filter) {
        int limit = filter.effectiveLimit();
        List<PaymentIntent> matching = new ArrayList<>();
        for (PaymentIntentEntity entity : intents.findAll(Sort.by(Sort.Direction.ASC, "id"))) {
            if (matches(filter, entity)) {
                if (filter.cursor() == null || entity.getId().compareTo(filter.cursor()) > 0) {
                    matching.add(entity.toDomain(decodeMetadata(entity)));
                }
            }
        }
        boolean hasMore = matching.size() > limit;
        List<PaymentIntent> page = hasMore ? matching.subList(0, limit) : matching;
        String nextCursor = hasMore ? page.get(page.size() - 1).id() : null;
        return new Page(page, nextCursor);
    }

    private static boolean matches(PaymentFilter filter, PaymentIntentEntity entity) {
        if (filter.state() != null && !filter.state().wireName().equals(entity.getState())) {
            return false;
        }
        if (filter.principalId() != null && !filter.principalId().equals(entity.getPrincipalId())) {
            return false;
        }
        if (filter.createdFrom() != null && entity.getCreatedAt().isBefore(filter.createdFrom())) {
            return false;
        }
        return filter.createdTo() == null || entity.getCreatedAt().isBefore(filter.createdTo());
    }

    private static String encodeMetadata(PaymentIntent intent) {
        if (intent.metadata().isEmpty()) {
            return "{}";
        }
        return JSON.writeValueAsString(intent.metadata());
    }

    private static Map<String, String> decodeMetadata(PaymentIntentEntity entity) {
        String json = entity.getMetadataJson();
        if (json == null || json.isBlank() || "{}".equals(json)) {
            return Map.of();
        }
        Map<String, String> decoded = JSON.readValue(json, METADATA_TYPE);
        return decoded == null ? Map.of() : Map.copyOf(decoded);
    }
}
