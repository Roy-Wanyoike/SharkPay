package com.sharkpay.payouts.storage;

import com.sharkpay.money.Money;
import com.sharkpay.payouts.domain.Destination;
import com.sharkpay.payouts.domain.Payout;
import com.sharkpay.payouts.domain.PayoutState;
import com.sharkpay.payouts.domain.Rail;
import com.sharkpay.payouts.domain.StateTransition;
import com.sharkpay.payouts.domain.Transfer;
import com.sharkpay.payouts.domain.TransferState;
import com.sharkpay.payouts.ports.IdempotencyStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.data.domain.Limit;

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
 * Covers the JPA port adapters without a database (mirrors the payments
 * exemplar's JpaAdaptersTest): the Spring Data interfaces are replaced by
 * Mockito-backed in-memory fakes with the same semantics as
 * V1__payouts_init.sql (PK payouts.id / transfers.id, append-only
 * payout_state_transitions / transfer_state_transitions keyed by bigserial
 * id, unique (scope, idempotency_key)). Verifies delegation, entity
 * mapping round-trips (money incl. fee_minor / non_refundable_fee_minor,
 * flattened destinations, the three ledger entry ids) and the
 * scheduler-query partial-index semantics.
 */
class JpaAdaptersTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final String POT_ID = "pot_0123456789abcdef0123456789abcdef";
    private static final String TRF_ID = "trf_0123456789abcdef0123456789abcdef";
    private static final String WALLET = "wal_0123456789abcdef0123456789abcdef";
    private static final String OTHER_WALLET = "wal_fedcba9876543210fedcba9876543210";

    // ── JpaPayoutRepository ────────────────────────────────────────────────

    @Test
    void saveInsertsTheSnapshotAndAppendsThePendingTransitionRows() {
        PayoutJpaRepository jpa = Mockito.mock(PayoutJpaRepository.class);
        PayoutTransitionJpaRepository transitions =
                Mockito.mock(PayoutTransitionJpaRepository.class);
        when(jpa.findById(POT_ID)).thenReturn(Optional.empty());
        when(jpa.save(any(PayoutEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transitions.save(any(PayoutTransitionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        JpaPayoutRepository adapter = new JpaPayoutRepository(jpa, transitions);

        Payout payout = payout();
        payout.accept(T0.plusSeconds(30), UUID.randomUUID(), T0);

        assertThat(adapter.save(payout)).isSameAs(payout);
        verify(jpa).save(any(PayoutEntity.class));
        // the acceptance transition row is appended exactly once
        verify(transitions).save(Mockito.argThat(entity -> entity.payoutId.equals(POT_ID)
                && "PENDING_RISK".equals(entity.toState)
                && "CREATED".equals(entity.fromState)
                && "risk_pass".equals(entity.trigger)
                && entity.createdAt.equals(T0)));
        // pending transitions drained — a second save appends nothing new
        assertThat(payout.pendingTransitions()).isEmpty();
        adapter.save(payout);
        verify(transitions, Mockito.times(1)).save(any(PayoutTransitionEntity.class));
    }

    @Test
    void saveUpdatesTheMutableColumnsOnTheExistingEntity() {
        PayoutJpaRepository jpa = Mockito.mock(PayoutJpaRepository.class);
        PayoutTransitionJpaRepository transitions =
                Mockito.mock(PayoutTransitionJpaRepository.class);
        PayoutEntity existing = PayoutEntity.fromDomain(payout());
        when(jpa.findById(POT_ID)).thenReturn(Optional.of(existing));
        when(jpa.save(any(PayoutEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        JpaPayoutRepository adapter = new JpaPayoutRepository(jpa, transitions);

        Payout payout = payout();
        payout.accept(T0.plusSeconds(30), UUID.randomUUID(), T0);
        payout.markSubmitted("honeycoin:hc_1", T0.plusSeconds(31));
        adapter.save(payout);

        assertThat(existing.state).isEqualTo(PayoutState.PROCESSING);
        assertThat(existing.providerRef).isEqualTo("honeycoin:hc_1");
        assertThat(existing.attempts).isZero();
        assertThat(existing.nextAttemptAt).isNull();
        // re-read maps the mutable columns back onto the domain
        Payout reread = existing.toDomain(List.of());
        assertThat(reread.providerRef()).isEqualTo("honeycoin:hc_1");
        assertThat(reread.state()).isEqualTo(PayoutState.PROCESSING);
    }

    @Test
    void saveDrainsSubmitFailureBookkeepingWithoutAuditRows() {
        PayoutJpaRepository jpa = Mockito.mock(PayoutJpaRepository.class);
        PayoutTransitionJpaRepository transitions =
                Mockito.mock(PayoutTransitionJpaRepository.class);
        when(jpa.findById(POT_ID)).thenReturn(Optional.of(PayoutEntity.fromDomain(payout())));
        when(jpa.save(any(PayoutEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        JpaPayoutRepository adapter = new JpaPayoutRepository(jpa, transitions);

        Payout payout = payout();
        payout.accept(T0.plusSeconds(30), UUID.randomUUID(), T0);
        adapter.save(payout);
        payout.recordSubmitFailure(T0.plusSeconds(90), T0.plusSeconds(1));
        adapter.save(payout);

        verify(transitions, Mockito.times(1)).save(any(PayoutTransitionEntity.class));
        assertThat(payout.attempts()).isEqualTo(1);
    }

    @Test
    void findByIdRehydratesTheTimelineInOrder() {
        PayoutJpaRepository jpa = Mockito.mock(PayoutJpaRepository.class);
        PayoutTransitionJpaRepository transitions =
                Mockito.mock(PayoutTransitionJpaRepository.class);
        PayoutEntity entity = PayoutEntity.fromDomain(payout());
        when(jpa.findById(POT_ID)).thenReturn(Optional.of(entity));
        when(transitions.findByPayoutIdOrderByIdAsc(POT_ID)).thenReturn(List.of(
                payoutTransitionRow("CREATED", "PENDING_RISK", "risk_pass", "system",
                        "held 505500 KES", T0),
                payoutTransitionRow("PENDING_RISK", "PROCESSING", "scheduler", "scheduler",
                        "submitted to mpesa:+254712345678", T0.plusSeconds(1))));
        JpaPayoutRepository adapter = new JpaPayoutRepository(jpa, transitions);

        Optional<Payout> found = adapter.findById(POT_ID);
        assertThat(found).isPresent();
        Payout loaded = found.orElseThrow();
        assertThat(loaded.id()).isEqualTo(POT_ID);
        assertThat(loaded.state()).isEqualTo(PayoutState.CREATED);
        assertThat(loaded.transitions()).hasSize(2);
        assertThat(loaded.transitions().get(0).from()).isEqualTo(PayoutState.CREATED);
        assertThat(loaded.transitions().get(1).to()).isEqualTo(PayoutState.PROCESSING);
        assertThat(loaded.transitions().get(1).actor()).isEqualTo("scheduler");
        // unknown id → empty
        assertThat(adapter.findById("pot_0123456789abcdef0123456789abcdee")).isEmpty();
    }

    @Test
    void schedulerQueriesDelegateWithTheIndexSemantics() {
        PayoutJpaRepository jpa = Mockito.mock(PayoutJpaRepository.class);
        PayoutTransitionJpaRepository transitions =
                Mockito.mock(PayoutTransitionJpaRepository.class);
        Payout due = payout();
        due.accept(T0.plusSeconds(5), UUID.randomUUID(), T0);
        Payout expired = payout("pot_0123456789abcdef0123456789abcdee");
        expired.accept(T0.plusSeconds(5), UUID.randomUUID(), T0);
        expired.markSubmitted("honeycoin:hc_2", T0);
        Payout inFlight = payout("pot_0123456789abcdef0123456789abcddf");
        inFlight.accept(T0.plusSeconds(5), UUID.randomUUID(), T0);
        inFlight.markSubmitted("honeycoin:hc_3", T0);
        when(jpa.findDueForRelease(Mockito.eq(T0), any(Limit.class)))
                .thenReturn(List.of(PayoutEntity.fromDomain(due)));
        when(jpa.findExpired(Mockito.eq(T0), any(Limit.class)))
                .thenReturn(List.of(PayoutEntity.fromDomain(expired)));
        when(jpa.findInFlight(any(Limit.class)))
                .thenReturn(List.of(PayoutEntity.fromDomain(inFlight)));
        when(jpa.countByState(PayoutState.PENDING_RISK)).thenReturn(2L);
        JpaPayoutRepository adapter = new JpaPayoutRepository(jpa, transitions);

        assertThat(adapter.findDueForRelease(T0, 10)).extracting(Payout::id)
                .containsExactly(POT_ID);
        assertThat(adapter.findExpired(T0, 10)).extracting(Payout::id)
                .containsExactly("pot_0123456789abcdef0123456789abcdee");
        assertThat(adapter.findInFlight(10)).extracting(Payout::id)
                .containsExactly("pot_0123456789abcdef0123456789abcddf");
        assertThat(adapter.countByState(PayoutState.PENDING_RISK)).isEqualTo(2L);
        // the bounded limit is passed through to the partial-index queries
        org.mockito.ArgumentCaptor<Limit> releaseLimit =
                org.mockito.ArgumentCaptor.forClass(Limit.class);
        verify(jpa).findDueForRelease(Mockito.eq(T0), releaseLimit.capture());
        org.mockito.ArgumentCaptor<Limit> expiryLimit =
                org.mockito.ArgumentCaptor.forClass(Limit.class);
        verify(jpa).findExpired(Mockito.eq(T0), expiryLimit.capture());
        org.mockito.ArgumentCaptor<Limit> pollLimit =
                org.mockito.ArgumentCaptor.forClass(Limit.class);
        verify(jpa).findInFlight(pollLimit.capture());
        assertThat(releaseLimit.getValue().max()).isEqualTo(10);
        assertThat(expiryLimit.getValue().max()).isEqualTo(10);
        assertThat(pollLimit.getValue().max()).isEqualTo(10);
    }

    @Test
    void payoutAdaptersValidateTheirDependencies() {
        PayoutJpaRepository jpa = Mockito.mock(PayoutJpaRepository.class);
        PayoutTransitionJpaRepository transitions =
                Mockito.mock(PayoutTransitionJpaRepository.class);
        assertThatThrownBy(() -> new JpaPayoutRepository(null, transitions))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("payoutJpaRepository is required");
        assertThatThrownBy(() -> new JpaPayoutRepository(jpa, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("payoutTransitionJpaRepository is required");
    }

    // ── JpaTransferRepository ──────────────────────────────────────────────

    @Test
    void transferSaveAppendsTransitionRowsAndFindByIdRehydrates() {
        TransferJpaRepository jpa = Mockito.mock(TransferJpaRepository.class);
        TransferTransitionJpaRepository transitions =
                Mockito.mock(TransferTransitionJpaRepository.class);
        when(jpa.findById(TRF_ID)).thenReturn(Optional.empty());
        when(jpa.save(any(TransferEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transitions.save(any(TransferTransitionEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        JpaTransferRepository adapter = new JpaTransferRepository(jpa, transitions);

        Transfer transfer = transfer();
        transfer.markSucceeded(UUID.randomUUID(), T0);
        adapter.save(transfer);
        verify(transitions).save(Mockito.argThat(entity -> entity.transferId.equals(TRF_ID)
                && "SUCCEEDED".equals(entity.toState)
                && "ledger_confirmation".equals(entity.trigger)));

        // re-load: update path + full history
        TransferEntity stored = TransferEntity.fromDomain(transfer);
        when(jpa.findById(TRF_ID)).thenReturn(Optional.of(stored));
        when(transitions.findByTransferIdOrderByIdAsc(TRF_ID)).thenReturn(List.of(
                transferTransitionRow("CREATED", "SUCCEEDED", "ledger_confirmation", "system",
                        null, T0)));
        Transfer loaded = adapter.findById(TRF_ID).orElseThrow();
        assertThat(loaded.id()).isEqualTo(TRF_ID);
        assertThat(loaded.state()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(loaded.transitions()).hasSize(1);
        assertThat(loaded.transitions().get(0).to()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(adapter.findById("trf_0123456789abcdef0123456789abcdee")).isEmpty();
    }

    @Test
    void transferAdaptersValidateTheirDependencies() {
        TransferJpaRepository jpa = Mockito.mock(TransferJpaRepository.class);
        TransferTransitionJpaRepository transitions =
                Mockito.mock(TransferTransitionJpaRepository.class);
        assertThatThrownBy(() -> new JpaTransferRepository(null, transitions))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("transferJpaRepository is required");
        assertThatThrownBy(() -> new JpaTransferRepository(jpa, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("transferTransitionJpaRepository is required");
    }

    // ── entity mapping round-trips ─────────────────────────────────────────

    @Test
    void payoutEntityRoundTripsEveryDestinationTypeAndTheFullMoneyShape() {
        for (Destination destination : List.of(
                new Destination("mpesa", "+254712345678", null, null, null, null, null, null),
                new Destination("bank", null, "12345", "ACC-991", "Jane Doe", "KE", null, null),
                new Destination("on_chain", null, null, null, null, null, "base",
                        "0x8f6c3b1e9d2a4f7b8e5c6a1b2c3d4e5f6a7b8c9d"))) {
            Payout payout = payout(destination);
            payout.accept(T0.plusSeconds(30), UUID.randomUUID(), T0);
            payout.markSubmitted("honeycoin:hc_1", T0.plusSeconds(1));
            payout.markSent(T0.plusSeconds(2));
            payout.markSucceeded(UUID.randomUUID(), T0.plusSeconds(3));
            payout.markReturned("msisdn_not_registered", UUID.randomUUID(), T0.plusSeconds(4));

            PayoutEntity entity = PayoutEntity.fromDomain(payout);
            Payout roundTripped = entity.toDomain(payout.transitions());

            assertThat(roundTripped.id()).isEqualTo(payout.id());
            assertThat(roundTripped.internalRef()).isEqualTo(payout.internalRef());
            assertThat(roundTripped.sourceWalletId()).isEqualTo(WALLET);
            assertThat(roundTripped.walletLedgerAccountId()).isEqualTo(
                    payout.walletLedgerAccountId());
            // money: amount, fee AND non-refundable fee survive the columns
            assertThat(roundTripped.amount()).isEqualTo(payout.amount());
            assertThat(roundTripped.fee()).isEqualTo(payout.fee());
            assertThat(roundTripped.nonRefundableFee()).isEqualTo(payout.nonRefundableFee());
            assertThat(roundTripped.amount().currency()).isEqualTo(payout.amount().currency());
            assertThat(roundTripped.rail()).isEqualTo(payout.rail());
            assertThat(roundTripped.destination()).isEqualTo(payout.destination());
            assertThat(roundTripped.state()).isEqualTo(PayoutState.RETURNED);
            assertThat(roundTripped.providerRef()).isEqualTo("honeycoin:hc_1");
            assertThat(roundTripped.returnReason()).isEqualTo("msisdn_not_registered");
            assertThat(roundTripped.attempts()).isEqualTo(payout.attempts());
            assertThat(roundTripped.executeAfter()).isEqualTo(payout.executeAfter());
            assertThat(roundTripped.expiresAt()).isEqualTo(payout.expiresAt());
            assertThat(roundTripped.holdEntryId()).isEqualTo(payout.holdEntryId());
            assertThat(roundTripped.settleEntryId()).isEqualTo(payout.settleEntryId());
            assertThat(roundTripped.returnEntryId()).isEqualTo(payout.returnEntryId());
            assertThat(roundTripped.metadata()).containsEntry("invoice", "INV-991");
            assertThat(roundTripped.createdAt()).isEqualTo(payout.createdAt());
            assertThat(roundTripped.updatedAt()).isEqualTo(payout.updatedAt());
            assertThat(roundTripped.transitions()).isEqualTo(payout.transitions());
            // the empty metadata map maps to a null JSON document
            assertThat(PayoutEntity.fromDomain(payout(Map.of())).metadata).isNull();
        }
    }

    @Test
    void payoutEntityCarriesTheRetryBookkeepingColumns() {
        Payout payout = payout();
        payout.accept(T0.plusSeconds(30), UUID.randomUUID(), T0);
        payout.recordSubmitFailure(T0.plusSeconds(95), T0.plusSeconds(5));
        PayoutEntity entity = PayoutEntity.fromDomain(payout);

        assertThat(entity.attempts).isEqualTo(1);
        assertThat(entity.nextAttemptAt).isEqualTo(T0.plusSeconds(95));
        assertThat(entity.exponent).isEqualTo(2);
        assertThat(entity.currency).isEqualTo("KES");

        Payout roundTripped = entity.toDomain(List.of());
        assertThat(roundTripped.attempts()).isEqualTo(1);
        assertThat(roundTripped.nextAttemptAt()).isEqualTo(T0.plusSeconds(95));
        // applyDomain refreshes an existing row wholesale
        PayoutEntity target = PayoutEntity.fromDomain(payout(Map.of()));
        target.applyDomain(payout);
        assertThat(target.attempts).isEqualTo(1);
        assertThat(target.id).isEqualTo(POT_ID);
    }

    @Test
    void transferEntityRoundTripsMoneyInclFeeMinorAndTerminalColumns() {
        Transfer transfer = transfer();
        transfer.markSucceeded(UUID.randomUUID(), T0);
        TransferEntity entity = TransferEntity.fromDomain(transfer);

        assertThat(entity.feeMinor).isZero(); // V1: internal transfers are free
        assertThat(entity.amountMinor).isEqualTo(25_000L);
        assertThat(entity.state).isEqualTo(TransferState.SUCCEEDED);
        assertThat(entity.entryId).isEqualTo(transfer.entryId());

        Transfer roundTripped = entity.toDomain(transfer.transitions());
        assertThat(roundTripped.id()).isEqualTo(TRF_ID);
        assertThat(roundTripped.amount()).isEqualTo(Money.of(25_000, "KES"));
        assertThat(roundTripped.fee()).isEqualTo(Money.zero("KES"));
        assertThat(roundTripped.state()).isEqualTo(TransferState.SUCCEEDED);
        assertThat(roundTripped.entryId()).isEqualTo(transfer.entryId());
        assertThat(roundTripped.metadata()).containsEntry("note", "rent");
        assertThat(roundTripped.createdAt()).isEqualTo(T0);
        assertThat(roundTripped.updatedAt()).isEqualTo(T0);

        // the failed terminal state and its reason survive too
        Transfer failed = transfer();
        failed.markFailed("insufficient_funds: wallet balance check failed", T0);
        TransferEntity failedEntity = TransferEntity.fromDomain(failed);
        assertThat(failedEntity.toDomain(List.of()).failureReason()).contains("insufficient_funds");

        // applyDomain refreshes an existing row
        TransferEntity target = TransferEntity.fromDomain(transfer(Map.of()));
        target.applyDomain(failed);
        assertThat(target.state).isEqualTo(TransferState.FAILED);
        assertThat(target.failureReason).contains("insufficient_funds");
    }

    @Test
    void transitionEntitiesCarryTheWireNamesAndKeepJpaConstructors() {
        Payout payout = payout();
        payout.accept(T0.plusSeconds(30), UUID.randomUUID(), T0);
        StateTransition transition = payout.transitions().get(0);
        PayoutTransitionEntity row = PayoutTransitionEntity.of(payout, transition);
        assertThat(row.payoutId).isEqualTo(POT_ID);
        assertThat(row.fromState).isEqualTo("CREATED");
        assertThat(row.toState).isEqualTo("PENDING_RISK");
        assertThat(row.trigger).isEqualTo("risk_pass");
        assertThat(row.actor).isEqualTo("system");
        assertThat(row.note).contains("510500");
        assertThat(row.createdAt).isEqualTo(T0);

        Transfer transfer = transfer();
        transfer.markSucceeded(UUID.randomUUID(), T0);
        TransferTransitionEntity transferRow = TransferTransitionEntity.of(transfer,
                transfer.transitions().get(0));
        assertThat(transferRow.transferId).isEqualTo(TRF_ID);
        assertThat(transferRow.toState).isEqualTo("SUCCEEDED");

        // Hibernate proxying needs the no-arg constructors
        assertThatCode(PayoutEntity::new).doesNotThrowAnyException();
        assertThatCode(TransferEntity::new).doesNotThrowAnyException();
        assertThatCode(PayoutTransitionEntity::new).doesNotThrowAnyException();
        assertThatCode(TransferTransitionEntity::new).doesNotThrowAnyException();
        assertThatCode(IdempotencyKeyEntity::new).doesNotThrowAnyException();
    }

    // ── JpaIdempotencyStore ────────────────────────────────────────────────

    @Test
    void idempotencyStoreFindsPutsAndRemoves() {
        IdempotencyKeyJpaRepository jpa = Mockito.mock(IdempotencyKeyJpaRepository.class);
        IdempotencyKeyEntity entity = IdempotencyKeyEntity.of("CREATE_PAYOUT", "key-1",
                new IdempotencyStore.StoredRequest("CREATE_PAYOUT|fingerprint", POT_ID), T0);
        when(jpa.findByScopeAndIdempotencyKey("CREATE_PAYOUT", "key-1"))
                .thenReturn(Optional.of(entity));
        JpaIdempotencyStore store = new JpaIdempotencyStore(jpa);

        Optional<IdempotencyStore.StoredRequest> found = store.find(
                IdempotencyStore.Scope.CREATE_PAYOUT, "key-1");
        assertThat(found).isPresent();
        assertThat(found.get().requestFingerprint()).isEqualTo("CREATE_PAYOUT|fingerprint");
        assertThat(found.get().entityId()).isEqualTo(POT_ID);

        store.put(IdempotencyStore.Scope.CANCEL_PAYOUT, "ck-1",
                new IdempotencyStore.StoredRequest("CANCEL_PAYOUT|pot_1", POT_ID));
        verify(jpa).saveAndFlush(any(IdempotencyKeyEntity.class));

        store.remove(IdempotencyStore.Scope.CANCEL_PAYOUT, "ck-1");
        verify(jpa).deleteByScopeAndIdempotencyKey("CANCEL_PAYOUT", "ck-1");

        assertThat(store.find(IdempotencyStore.Scope.PROVIDER_RESULT, "nope")).isEmpty();
        assertThatThrownBy(() -> new JpaIdempotencyStore(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void concurrentPutRacesAreSwallowedAsNoOps() {
        IdempotencyKeyJpaRepository jpa = Mockito.mock(IdempotencyKeyJpaRepository.class);
        when(jpa.saveAndFlush(any(IdempotencyKeyEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        JpaIdempotencyStore store = new JpaIdempotencyStore(jpa);

        // the loser of the unique-constraint race replays the winner's record
        assertThatCode(() -> store.put(IdempotencyStore.Scope.CREATE_PAYOUT, "key-1",
                new IdempotencyStore.StoredRequest("f", POT_ID)))
                .doesNotThrowAnyException();
    }

    @Test
    void removingAnAbsentKeyIsANoOp() {
        IdempotencyKeyJpaRepository jpa = Mockito.mock(IdempotencyKeyJpaRepository.class);
        Mockito.doThrow(new EmptyResultDataAccessException("no row", 1))
                .when(jpa).deleteByScopeAndIdempotencyKey("CREATE_PAYOUT", "never-stored");
        JpaIdempotencyStore store = new JpaIdempotencyStore(jpa);

        assertThatCode(() -> store.remove(IdempotencyStore.Scope.CREATE_PAYOUT, "never-stored"))
                .doesNotThrowAnyException();
    }

    @Test
    void idempotencyKeysCarryCompositeEquality() {
        IdempotencyKeyPk a = new IdempotencyKeyPk("CREATE_PAYOUT", "k");
        IdempotencyKeyPk b = new IdempotencyKeyPk("CREATE_PAYOUT", "k");
        IdempotencyKeyPk c = new IdempotencyKeyPk("CANCEL_PAYOUT", "k");
        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(c);
        assertThat(a).isEqualTo(a);
        assertThat(a).isNotEqualTo("CREATE_PAYOUT:k");
        assertThat(a.toString()).contains("CREATE_PAYOUT").contains("k");
        assertThatCode(IdempotencyKeyPk::new).doesNotThrowAnyException();

        IdempotencyKeyEntity entity = IdempotencyKeyEntity.of("CREATE_PAYOUT", "key-1",
                new IdempotencyStore.StoredRequest("fingerprint", POT_ID), T0);
        assertThat(entity).isEqualTo(IdempotencyKeyEntity.of("CREATE_PAYOUT", "key-1",
                new IdempotencyStore.StoredRequest("other", "pot_0123456789abcdef0123456789abcdee"),
                T0));
        assertThat(entity).isNotEqualTo(IdempotencyKeyEntity.of("CREATE_PAYOUT", "key-2",
                new IdempotencyStore.StoredRequest("fingerprint", POT_ID), T0));
        assertThat(entity).hasSameHashCodeAs(IdempotencyKeyEntity.of("CREATE_PAYOUT", "key-1",
                new IdempotencyStore.StoredRequest("other", POT_ID), T0));
        assertThat(entity.requestFingerprint).isEqualTo("fingerprint");
        assertThat(entity.entityId).isEqualTo(POT_ID);
        assertThat(entity.createdAt).isEqualTo(T0);
        assertThat(entity.toStoredRequest().entityId()).isEqualTo(POT_ID);
    }

    // ── fixtures ───────────────────────────────────────────────────────────

    /** A hand-built transition row (the bigserial id is assigned by the DB). */
    private static PayoutTransitionEntity payoutTransitionRow(String from, String to,
                                                                String trigger, String actor,
                                                                String note, Instant at) {
        PayoutTransitionEntity entity = new PayoutTransitionEntity();
        entity.payoutId = POT_ID;
        entity.fromState = from;
        entity.toState = to;
        entity.trigger = trigger;
        entity.actor = actor;
        entity.note = note;
        entity.createdAt = at;
        return entity;
    }

    private static TransferTransitionEntity transferTransitionRow(String from, String to,
                                                                   String trigger, String actor,
                                                                   String note, Instant at) {
        TransferTransitionEntity entity = new TransferTransitionEntity();
        entity.transferId = TRF_ID;
        entity.fromState = from;
        entity.toState = to;
        entity.trigger = trigger;
        entity.actor = actor;
        entity.note = note;
        entity.createdAt = at;
        return entity;
    }

    private static Payout payout() {
        return payout(POT_ID);
    }

    private static Payout payout(String id) {
        return payout(id, Map.of("invoice", "INV-991"));
    }

    private static Payout payout(Map<String, String> metadata) {
        return payout(POT_ID, metadata);
    }

    private static Payout payout(Destination destination) {
        return payout(POT_ID, Map.of("invoice", "INV-991"), destination);
    }

    private static Payout payout(String id, Map<String, String> metadata) {
        return payout(id, metadata, new Destination("mpesa", "+254712345678", null, null, null,
                null, null, null));
    }

    private static Payout payout(Map<String, String> metadata, Destination destination) {
        return payout(POT_ID, metadata, destination);
    }

    private static Payout payout(String id, Map<String, String> metadata, Destination destination) {
        // on-chain serves stablecoins only; the fiat rails serve KES here
        String currency = destination.rail() == Rail.ON_CHAIN ? "USDC" : "KES";
        return Payout.newPayout(id, UUID.nameUUIDFromBytes(new byte[]{1}), WALLET,
                UUID.nameUUIDFromBytes(new byte[]{2}), Money.of(500_000, currency),
                Money.of(10_500, currency), Money.of(5_500, currency), destination.rail(),
                destination, metadata, T0.plusSeconds(30), T0.plusSeconds(900), T0);
    }

    private static Transfer transfer() {
        return transfer(Map.of("note", "rent"));
    }

    private static Transfer transfer(Map<String, String> metadata) {
        return Transfer.instantiate(TRF_ID, UUID.nameUUIDFromBytes(new byte[]{3}), WALLET,
                OTHER_WALLET, Money.of(25_000, "KES"), metadata, T0);
    }
}
