package com.sharkpay.payments.storage;

import com.sharkpay.money.Money;
import com.sharkpay.payments.domain.Destination;
import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.domain.Rail;
import com.sharkpay.payments.domain.StateTransition;
import com.sharkpay.payments.ports.IdempotencyStore;
import com.sharkpay.payments.ports.PaymentRepository.PaymentFilter;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the JPA port adapters without a database: the Spring Data
 * interfaces are replaced by Mockito-backed in-memory fakes with the same
 * semantics as the Flyway schema (PK payment_intents.id; append-only
 * payment_state_transitions keyed (payment_id, seq); unique
 * (scope, idempotency_key)). Verifies delegation + entity mapping + the
 * wallet-consistent in-memory listing stance.
 */
class JpaAdaptersTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final String PAY_ID = "pay_0123456789abcdef0123456789abcdef";
    private static final String WALLET = "wal_0123456789abcdef0123456789abcdef";

    // ── JpaPaymentRepository ────────────────────────────────────────────────

    @Test
    void saveInsertsTheSnapshotAndAppendsTheTransitionRows() {
        PaymentIntentJpaRepository intents = Mockito.mock(PaymentIntentJpaRepository.class);
        PaymentStateTransitionJpaRepository transitions =
                Mockito.mock(PaymentStateTransitionJpaRepository.class);
        when(intents.findById(PAY_ID)).thenReturn(Optional.empty());
        when(intents.save(any(PaymentIntentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(transitions.save(any(PaymentStateTransitionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        JpaPaymentRepository adapter = new JpaPaymentRepository(intents, transitions);

        PaymentIntent intent = intent();
        PaymentIntent saved = adapter.save(intent);

        assertThat(saved).isEqualTo(intent);
        verify(intents).save(any(PaymentIntentEntity.class));
        // creation row appended with the wire names + entry id
        verify(transitions).save(Mockito.argThat(entity -> entity.getPaymentId().equals(PAY_ID)
                && entity.getSeq() == 1 && entity.getFromState() == null
                && entity.getToState().equals("CREATED") && entity.getReason().contains("honeycoin")
                && entity.getOccurredAt().equals(T0)));

        // second save: update path + only the new transition rows
        adapter.save(intent);
        verify(intents, Mockito.times(2)).save(any(PaymentIntentEntity.class));
        verify(transitions, Mockito.times(1)).save(any(PaymentStateTransitionEntity.class));
    }

    @Test
    void saveUpdatesTheMutableColumnsOnTheExistingEntity() {
        PaymentIntentJpaRepository intents = Mockito.mock(PaymentIntentJpaRepository.class);
        PaymentStateTransitionJpaRepository transitions =
                Mockito.mock(PaymentStateTransitionJpaRepository.class);
        PaymentIntentEntity existing = PaymentIntentEntity.fromDomain(intent(), "{}");
        when(intents.findById(PAY_ID)).thenReturn(Optional.of(existing));
        when(intents.save(any(PaymentIntentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        JpaPaymentRepository adapter = new JpaPaymentRepository(intents, transitions);

        PaymentIntent intent = intent();
        intent.markPendingProvider("hld_1", UUID.randomUUID(), T0);
        intent.recordProviderHandoff("honeycoin", "hc_1", T0);
        adapter.save(intent);

        assertThat(existing.getState()).isEqualTo("PENDING_PROVIDER");
        assertThat(existing.getTransitionSeq()).isEqualTo(2);
        // the hold id is exposed through the mapped domain
        PaymentIntent reread = existing.toDomain(Map.of());
        assertThat(reread.holdId()).isEqualTo("hld_1");
    }

    @Test
    void findByIdMapsTheEntityAndDecodesMetadata() {
        PaymentIntentJpaRepository intents = Mockito.mock(PaymentIntentJpaRepository.class);
        PaymentStateTransitionJpaRepository transitions =
                Mockito.mock(PaymentStateTransitionJpaRepository.class);
        PaymentIntent source = intent(Map.of("order_id", "A-7731"));
        PaymentIntentEntity entity = PaymentIntentEntity.fromDomain(source,
                "{\"order_id\":\"A-7731\"}");
        when(intents.findById(PAY_ID)).thenReturn(Optional.of(entity));
        JpaPaymentRepository adapter = new JpaPaymentRepository(intents, transitions);

        assertThat(adapter.findById(PAY_ID)).isPresent();
        PaymentIntent loaded = adapter.findById(PAY_ID).orElseThrow();
        assertThat(loaded.metadata()).containsEntry("order_id", "A-7731");
        assertThat(loaded.id()).isEqualTo(PAY_ID);
        assertThat(adapter.findById("pay_0123456789abcdef0123456789abcdee")).isEmpty();
    }

    @Test
    void transitionsOfMapsTheAppendOnlyRowsInOrder() {
        PaymentIntentJpaRepository intents = Mockito.mock(PaymentIntentJpaRepository.class);
        PaymentStateTransitionJpaRepository transitions =
                Mockito.mock(PaymentStateTransitionJpaRepository.class);
        when(transitions.findByPaymentIdOrderBySeqAsc(PAY_ID)).thenReturn(List.of(
                new PaymentStateTransitionEntity(PAY_ID, 1, null, "CREATED", "created rail=honeycoin",
                        null, T0),
                new PaymentStateTransitionEntity(PAY_ID, 2, "CREATED", "PENDING_PROVIDER",
                        "risk_pass_hold_placed", UUID.randomUUID(), T0)));
        JpaPaymentRepository adapter = new JpaPaymentRepository(intents, transitions);

        List<StateTransition> timeline = adapter.transitionsOf(PAY_ID);
        assertThat(timeline).hasSize(2);
        assertThat(timeline.get(0).from()).isNull();
        assertThat(timeline.get(0).to()).isEqualTo(PaymentState.CREATED);
        assertThat(timeline.get(1).from()).isEqualTo(PaymentState.CREATED);
        assertThat(timeline.get(1).to()).isEqualTo(PaymentState.PENDING_PROVIDER);
        assertThat(timeline.get(1).seq()).isEqualTo(2);
    }

    @Test
    void listFiltersPaginatesAndCursorsInMemory() {
        PaymentIntentJpaRepository intents = Mockito.mock(PaymentIntentJpaRepository.class);
        PaymentStateTransitionJpaRepository transitions =
                Mockito.mock(PaymentStateTransitionJpaRepository.class);
        UUID sharedPrincipal = UUID.randomUUID();
        PaymentIntent a = intent("pay_0000000000000000000001", sharedPrincipal);
        PaymentIntent b = intent("pay_0000000000000000000002", sharedPrincipal);
        PaymentIntent c = intent("pay_0000000000000000000003", sharedPrincipal);
        Map<String, PaymentIntentEntity> store = new HashMap<>();
        for (PaymentIntent intent : List.of(a, b, c)) {
            store.put(intent.id(), PaymentIntentEntity.fromDomain(intent, "{}"));
        }
        when(intents.findAll(Mockito.any(Sort.class))).thenAnswer(inv -> {
            Sort sort = inv.getArgument(0);
            List<PaymentIntentEntity> all = new java.util.ArrayList<>(store.values());
            all.sort(java.util.Comparator.comparing(PaymentIntentEntity::getId));
            return sort.getOrderFor("id").getDirection().isAscending() ? all
                    : all.reversed();
        });
        JpaPaymentRepository adapter = new JpaPaymentRepository(intents, transitions);

        var page = adapter.list(new PaymentFilter(PaymentState.CREATED, null, null, null, 2, null));
        assertThat(page.items()).extracting(PaymentIntent::id)
                .containsExactly("pay_0000000000000000000001", "pay_0000000000000000000002");
        assertThat(page.nextCursor()).isEqualTo("pay_0000000000000000000002");

        var next = adapter.list(new PaymentFilter(PaymentState.CREATED, null, null, null, 2,
                "pay_0000000000000000000002"));
        assertThat(next.items()).extracting(PaymentIntent::id)
                .containsExactly("pay_0000000000000000000003");
        assertThat(next.nextCursor()).isNull();

        var principalPage = adapter.list(new PaymentFilter(null, a.principalId(), null, null, 50,
                null));
        assertThat(principalPage.items()).hasSize(3); // same principal everywhere here

        var emptyPage = adapter.list(new PaymentFilter(PaymentState.SUCCEEDED, null, null, null,
                50, null));
        assertThat(emptyPage.items()).isEmpty();

        assertThatThrownBy(() -> new PaymentFilter(null, null, null, null, 0, null).effectiveLimit())
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(new PaymentFilter(null, null, null, null, null, null).effectiveLimit())
                .isEqualTo(50);
    }

    @Test
    void metadataEncodesToAndDecodesFromTheJsonDocument() {
        PaymentIntentJpaRepository intents = Mockito.mock(PaymentIntentJpaRepository.class);
        PaymentStateTransitionJpaRepository transitions =
                Mockito.mock(PaymentStateTransitionJpaRepository.class);
        when(intents.findById(PAY_ID)).thenReturn(Optional.empty());
        when(intents.save(any(PaymentIntentEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(transitions.save(any(PaymentStateTransitionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        JpaPaymentRepository adapter = new JpaPaymentRepository(intents, transitions);

        PaymentIntent withMetadata = intent(Map.of("order_id", "A-7731", "k", "v"));
        adapter.save(withMetadata);
        var savedEntity = Mockito.mockingDetails(intents).getInvocations().stream()
                .filter(inv -> "save".equals(inv.getMethod().getName()))
                .map(inv -> (PaymentIntentEntity) inv.getArgument(0)).findFirst().orElseThrow();
        assertThat(savedEntity.getMetadataJson()).contains("order_id");

        PaymentIntent empty = intent();
        adapter.save(empty);
        var bareEntity = Mockito.mockingDetails(intents).getInvocations().stream()
                .filter(inv -> "save".equals(inv.getMethod().getName()))
                .map(inv -> (PaymentIntentEntity) inv.getArgument(0)).skip(1).findFirst()
                .orElseThrow();
        assertThat(bareEntity.getMetadataJson()).isEqualTo("{}");
    }

    // ── entity mapping ──────────────────────────────────────────────────────

    @Test
    void entityMappingRoundTripsAllDestinationTypes() {
        for (Destination destination : List.of(Destination.internalWallet(WALLET),
                Destination.externalRail("msisdn:+254712345678"),
                Destination.fxQuote("fxq_0123456789abcdef0123456789abcdef"))) {
            PaymentIntent intent = intent(destination);
            PaymentIntentEntity entity = PaymentIntentEntity.fromDomain(intent, "{}");
            PaymentIntent roundTripped = entity.toDomain(Map.of());
            assertThat(roundTripped.id()).isEqualTo(intent.id());
            assertThat(roundTripped.state()).isEqualTo(intent.state());
            assertThat(roundTripped.destination()).isEqualTo(intent.destination());
            assertThat(roundTripped.amount()).isEqualTo(intent.amount());
            assertThat(roundTripped.fee()).isEqualTo(intent.fee());
            assertThat(roundTripped.rail()).isEqualTo(intent.rail());
            assertThat(roundTripped.idempotencyKey()).isEqualTo(intent.idempotencyKey());
            assertThat(roundTripped.expiresAt()).isEqualTo(intent.expiresAt());
            assertThat(roundTripped.createdAt()).isEqualTo(intent.createdAt());
            assertThat(roundTripped.updatedAt()).isEqualTo(intent.updatedAt());
            assertThat(roundTripped.transitionSeq()).isEqualTo(intent.transitionSeq());
        }
    }

    @Test
    void entityMappingCarriesTheFullSagaState() {
        PaymentIntent intent = intent();
        intent.markPendingProvider("hld_1", UUID.randomUUID(), T0);
        intent.markProcessing(T0);
        intent.markSucceeded(UUID.randomUUID(), T0);
        intent.markReversed("ops", UUID.randomUUID(), Money.of(60_000, "KES"), T0);

        PaymentIntentEntity entity = PaymentIntentEntity.fromDomain(intent, "{}");
        PaymentIntent roundTripped = entity.toDomain(Map.of());

        assertThat(roundTripped.holdId()).isEqualTo("hld_1");
        assertThat(roundTripped.holdEntryId()).isEqualTo(intent.holdEntryId());
        assertThat(roundTripped.captureEntryId()).isEqualTo(intent.captureEntryId());
        assertThat(roundTripped.reversalEntryId()).isEqualTo(intent.reversalEntryId());
        assertThat(roundTripped.reversedAmount()).isEqualTo(Money.of(60_000, "KES"));
        assertThat(roundTripped.state()).isEqualTo(PaymentState.REVERSED);
        assertThat(roundTripped.drainPendingTransitions()).isEmpty();

        // applyDomain refreshes the mutable columns of an existing row
        PaymentIntentEntity target = PaymentIntentEntity.fromDomain(intent(), "{}");
        target.applyDomain(roundTripped);
        assertThat(target.getState()).isEqualTo("REVERSED");
        assertThat(target.getTransitionSeq()).isEqualTo(roundTripped.transitionSeq());
    }

    // ── JpaIdempotencyStore ─────────────────────────────────────────────────

    @Test
    void idempotencyStoreFindsPutsAndRemoves() {
        IdempotencyKeyJpaRepository jpa = Mockito.mock(IdempotencyKeyJpaRepository.class);
        IdempotencyKeyEntity entity = new IdempotencyKeyEntity(
                new IdempotencyKeyPk("CREATE_PAYMENT", "key-1"),
                "CREATE_PAYMENT|fingerprint", PAY_ID, T0);
        when(jpa.findById(new IdempotencyKeyPk("CREATE_PAYMENT", "key-1")))
                .thenReturn(Optional.of(entity));
        JpaIdempotencyStore store = new JpaIdempotencyStore(jpa);

        Optional<IdempotencyStore.StoredRequest> found = store.find(
                IdempotencyStore.Scope.CREATE_PAYMENT, "key-1");
        assertThat(found).isPresent();
        assertThat(found.get().requestFingerprint()).isEqualTo("CREATE_PAYMENT|fingerprint");
        assertThat(found.get().entityId()).isEqualTo(PAY_ID);

        store.put(IdempotencyStore.Scope.CANCEL_PAYMENT, "ck-1",
                new IdempotencyStore.StoredRequest("CANCEL_PAYMENT|pay_1", PAY_ID));
        verify(jpa).saveAndFlush(any(IdempotencyKeyEntity.class));

        store.remove(IdempotencyStore.Scope.CANCEL_PAYMENT, "ck-1");
        verify(jpa).deleteById(new IdempotencyKeyPk("CANCEL_PAYMENT", "ck-1"));

        assertThat(store.find(IdempotencyStore.Scope.REVERSE_PAYMENT, "nope")).isEmpty();
    }

    @Test
    void concurrentPutRacesAreSwallowedAsNoOps() {
        IdempotencyKeyJpaRepository jpa = Mockito.mock(IdempotencyKeyJpaRepository.class);
        when(jpa.saveAndFlush(any(IdempotencyKeyEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        JpaIdempotencyStore store = new JpaIdempotencyStore(jpa);

        assertThatCode(() -> store.put(IdempotencyStore.Scope.CREATE_PAYMENT, "key-1",
                new IdempotencyStore.StoredRequest("f", PAY_ID)))
                .doesNotThrowAnyException(); // the loser replays the winner's record
    }

    @Test
    void compositeKeysCarryEquality() {
        IdempotencyKeyPk a = new IdempotencyKeyPk("CREATE_PAYMENT", "k");
        IdempotencyKeyPk b = new IdempotencyKeyPk("CREATE_PAYMENT", "k");
        IdempotencyKeyPk c = new IdempotencyKeyPk("CANCEL_PAYMENT", "k");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a.toString()).contains("CREATE_PAYMENT").contains("k");
        assertThat(a.getIdempotencyKey()).isEqualTo("k");
        assertThat(a.getScope()).isEqualTo("CREATE_PAYMENT");
        // identity short-circuit, foreign types, and the JPA-required no-arg
        // constructors (Hibernate proxying)
        assertThat(a).isEqualTo(a);
        assertThat(a).isNotEqualTo("CREATE_PAYMENT:k");
        assertThatCode(IdempotencyKeyPk::new).doesNotThrowAnyException();

        PaymentStateTransitionId t1 = new PaymentStateTransitionId(PAY_ID, 3);
        PaymentStateTransitionId t2 = new PaymentStateTransitionId(PAY_ID, 3);
        PaymentStateTransitionId t3 = new PaymentStateTransitionId(PAY_ID, 4);
        assertThat(t1).isEqualTo(t2).hasSameHashCodeAs(t2).isNotEqualTo(t3);
        assertThat(t1.toString()).contains(PAY_ID).contains("3");
        assertThat(t1.getPaymentId()).isEqualTo(PAY_ID);
        assertThat(t1.getSeq()).isEqualTo(3);
        assertThat(t1).isEqualTo(t1);
        assertThat(t1).isNotEqualTo(new Object());
        assertThatCode(PaymentStateTransitionId::new).doesNotThrowAnyException();
    }

    @Test
    void listFiltersByPrincipalAndCreatedRange() {
        PaymentIntentJpaRepository intents = Mockito.mock(PaymentIntentJpaRepository.class);
        PaymentStateTransitionJpaRepository transitions =
                Mockito.mock(PaymentStateTransitionJpaRepository.class);
        UUID principalA = UUID.randomUUID();
        UUID principalB = UUID.randomUUID();
        Map<String, PaymentIntentEntity> store = new HashMap<>();
        store.put("pay_0000000000000000000001",
                PaymentIntentEntity.fromDomain(intent("pay_0000000000000000000001", principalA,
                        T0), "{}"));
        store.put("pay_0000000000000000000002",
                PaymentIntentEntity.fromDomain(intent("pay_0000000000000000000002", principalB,
                        T0.plusSeconds(600)), "{}"));
        store.put("pay_0000000000000000000003",
                PaymentIntentEntity.fromDomain(intent("pay_0000000000000000000003", principalA,
                        T0.plusSeconds(1_200)), "{}"));
        when(intents.findAll(Mockito.any(Sort.class))).thenAnswer(inv -> {
            Sort sort = inv.getArgument(0);
            List<PaymentIntentEntity> all = new java.util.ArrayList<>(store.values());
            all.sort(java.util.Comparator.comparing(PaymentIntentEntity::getId));
            return sort.getOrderFor("id").getDirection().isAscending() ? all : all.reversed();
        });
        JpaPaymentRepository adapter = new JpaPaymentRepository(intents, transitions);

        // principal filter: only that principal's rows
        assertThat(adapter.list(new PaymentFilter(null, principalA, null, null, 50, null))
                .items()).extracting(PaymentIntent::id)
                .containsExactly("pay_0000000000000000000001", "pay_0000000000000000000003");

        // created_from: rows created at or after the bound
        assertThat(adapter.list(new PaymentFilter(null, null, T0.plusSeconds(300), null, 50,
                null)).items()).extracting(PaymentIntent::id)
                .containsExactly("pay_0000000000000000000002", "pay_0000000000000000000003");

        // created_to: rows created strictly before the bound
        assertThat(adapter.list(new PaymentFilter(null, null, null, T0.plusSeconds(900), 50,
                null)).items()).extracting(PaymentIntent::id)
                .containsExactly("pay_0000000000000000000001", "pay_0000000000000000000002");

        // combined principal + range narrows to exactly one row
        assertThat(adapter.list(new PaymentFilter(null, principalA, T0.plusSeconds(900), null,
                50, null)).items()).extracting(PaymentIntent::id)
                .containsExactly("pay_0000000000000000000003");
    }

    @Test
    void aNullOrBlankMetadataDocumentDecodesToNoMetadata() {
        // pre-Flyway / restored rows may carry a null metadata_json column
        PaymentIntentJpaRepository intents = Mockito.mock(PaymentIntentJpaRepository.class);
        PaymentStateTransitionJpaRepository transitions =
                Mockito.mock(PaymentStateTransitionJpaRepository.class);
        PaymentIntentEntity bare = PaymentIntentEntity.fromDomain(intent(), null);
        PaymentIntentEntity blank = PaymentIntentEntity.fromDomain(intent(), "  ");
        when(intents.findById(PAY_ID)).thenReturn(Optional.of(bare));
        assertThat(new JpaPaymentRepository(intents, transitions).findById(PAY_ID).orElseThrow()
                .metadata()).isEmpty();
        when(intents.findById(PAY_ID)).thenReturn(Optional.of(blank));
        assertThat(new JpaPaymentRepository(intents, transitions).findById(PAY_ID).orElseThrow()
                .metadata()).isEmpty();
    }

    @Test
    void entitiesKeepTheJpaRequiredNoArgConstructorsAndAccessors() {
        // JPA proxies and Hibernate instantiations need the no-arg
        // constructors; a regression here only shows up at runtime
        assertThatCode(PaymentIntentEntity::new).doesNotThrowAnyException();
        assertThatCode(PaymentStateTransitionEntity::new).doesNotThrowAnyException();
        assertThatCode(IdempotencyKeyEntity::new).doesNotThrowAnyException();

        // the entity accessors surface exactly what fromDomain persisted
        PaymentIntentEntity entity = PaymentIntentEntity.fromDomain(intent(), "{}");
        assertThat(entity.getId()).isEqualTo(PAY_ID);
        assertThat(entity.getInternalId()).isNotNull();
        assertThat(entity.getPrincipalId()).isNotNull();
        assertThat(entity.getState()).isEqualTo("CREATED");
        assertThat(entity.getCurrency()).isEqualTo("KES");
        assertThat(entity.getCreatedAt()).isEqualTo(T0);
        assertThat(entity.getExpiresAt()).isEqualTo(T0.plusSeconds(900));
        assertThat(entity.getTransitionSeq()).isEqualTo(1);
        assertThat(entity.getMetadataJson()).isEqualTo("{}");

        PaymentStateTransitionEntity transition =
                new PaymentStateTransitionEntity(PAY_ID, 1, null, "CREATED", "created", null, T0);
        assertThat(transition.getPaymentId()).isEqualTo(PAY_ID);
        assertThat(transition.getSeq()).isEqualTo(1);
        assertThat(transition.getFromState()).isNull();
        assertThat(transition.getToState()).isEqualTo("CREATED");
        assertThat(transition.getReason()).isEqualTo("created");
        assertThat(transition.getEntryId()).isNull();
        assertThat(transition.getOccurredAt()).isEqualTo(T0);

        IdempotencyKeyEntity keyEntity = new IdempotencyKeyEntity(
                new IdempotencyKeyPk("CREATE_PAYMENT", "key-1"), "fingerprint", PAY_ID, T0);
        assertThat(keyEntity.getId()).isEqualTo(new IdempotencyKeyPk("CREATE_PAYMENT", "key-1"));
        assertThat(keyEntity.getRequestFingerprint()).isEqualTo("fingerprint");
        assertThat(keyEntity.getEntityId()).isEqualTo(PAY_ID);
        assertThat(keyEntity.getCreatedAt()).isEqualTo(T0);
    }

    // ── fixtures ────────────────────────────────────────────────────────────

    private static PaymentIntent intent() {
        return intent(PAY_ID, Map.of());
    }

    private static PaymentIntent intent(Map<String, String> metadata) {
        return intent(PAY_ID, metadata);
    }

    private static PaymentIntent intent(String id) {
        return intent(id, Map.of());
    }

    private static PaymentIntent intent(String id, UUID principalId) {
        return intent(id, principalId, T0);
    }

    private static PaymentIntent intent(String id, UUID principalId, Instant createdAt) {
        return PaymentIntent.newIntent(id, UUID.randomUUID(), principalId, null,
                Destination.internalWallet(WALLET), Money.of(150_000, "KES"),
                Money.of(750, "KES"), "k", Rail.HONEYCOIN, createdAt.plusSeconds(900), Map.of(),
                createdAt);
    }

    private static PaymentIntent intent(String id, Map<String, String> metadata) {
        return PaymentIntent.newIntent(id, UUID.randomUUID(), UUID.randomUUID(), null,
                Destination.internalWallet(WALLET), Money.of(150_000, "KES"),
                Money.of(750, "KES"), "k", Rail.HONEYCOIN, T0.plusSeconds(900), metadata, T0);
    }

    private static PaymentIntent intent(Destination destination) {
        return PaymentIntent.newIntent(PAY_ID, UUID.randomUUID(), UUID.randomUUID(), null,
                destination, Money.of(150_000, "KES"), Money.of(750, "KES"), "k",
                Rail.HONEYCOIN, T0.plusSeconds(900), Map.of(), T0);
    }
}
