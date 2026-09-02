package com.sharkpay.reconciliation.storage;

import com.sharkpay.money.Money;
import com.sharkpay.reconciliation.domain.BreakState;
import com.sharkpay.reconciliation.domain.BreakType;
import com.sharkpay.reconciliation.domain.CompensationEntry;
import com.sharkpay.reconciliation.domain.CompensationLeg;
import com.sharkpay.reconciliation.domain.DetectedBreak;
import com.sharkpay.reconciliation.domain.PostingDirection;
import com.sharkpay.reconciliation.domain.ReconBreak;
import com.sharkpay.reconciliation.domain.ReconRun;
import com.sharkpay.reconciliation.domain.ReconRunState;
import com.sharkpay.reconciliation.domain.SettlementReport;
import com.sharkpay.reconciliation.ports.IdempotencyStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the JPA port adapters without a database: the Spring Data
 * interfaces are replaced by Mockito mocks with the schema's semantics
 * (id-keyed upsert → applyDomain on update; unique (scope, key) on the
 * idempotency table). Verifies delegation + mapping + query shapes.
 */
class JpaAdaptersTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-01T11:00:00Z");
    private static final java.time.Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final java.time.Instant TO = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void runAdapterDelegatesAndUpsertsInPlace() {
        ReconRunJpaRepository jpa = Mockito.mock(ReconRunJpaRepository.class);
        JpaReconRunRepository adapter = new JpaReconRunRepository(jpa);
        ReconRun run = ReconRun.start("run_01", "honeycoin",
                new com.sharkpay.reconciliation.domain.ReconWindow(FROM, TO), T0);

        // insert path: unknown id → fromDomain
        when(jpa.findById("run_01")).thenReturn(Optional.empty());
        when(jpa.save(any(ReconRunEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        adapter.save(run);
        verify(jpa).save(any(ReconRunEntity.class));

        // update path: known id → applyDomain on the persisted row
        when(jpa.findById("run_01")).thenReturn(Optional.of(ReconRunEntity.fromDomain(run)));
        run.complete(new ReconRun.Counts(2, 1, 1, 1), T1);
        adapter.save(run);
        verify(jpa, times(2)).save(any(ReconRunEntity.class));

        when(jpa.findById("run_01")).thenReturn(Optional.of(ReconRunEntity.fromDomain(run)));
        assertThat(adapter.findById("run_01")).isPresent();
        assertThat(adapter.findById("run_01").get().state()).isEqualTo(ReconRunState.COMPLETED);
        assertThat(adapter.findById("run_unknown")).isEmpty();

        // list delegation
        when(jpa.findByProviderOrderByStartedAtDescIdDesc("honeycoin"))
                .thenReturn(List.of(ReconRunEntity.fromDomain(run)));
        assertThat(adapter.listByProvider("honeycoin")).hasSize(1);
        assertThat(adapter.listByProvider("honeycoin").get(0).providerLines()).isEqualTo(2);
    }

    @Test
    void breakAdapterDelegatesMapsAndQueries() {
        ReconBreakJpaRepository jpa = Mockito.mock(ReconBreakJpaRepository.class);
        JpaReconBreakRepository adapter = new JpaReconBreakRepository(jpa);

        DetectedBreak detected = new DetectedBreak(BreakType.AMOUNT_MISMATCH, "hc_1", "int_1",
                Money.of(150_000, "KES"), Money.of(149_500, "KES"), Money.of(500, "KES"),
                Money.of(500, "KES"), "CONFIRMED", "CONFIRMED");
        ReconBreak break_ = ReconBreak.detect("brk_01", "run_01", "honeycoin", detected, T0);

        // insert path
        when(jpa.findById("brk_01")).thenReturn(Optional.empty());
        when(jpa.save(any(ReconBreakEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        adapter.save(break_);
        verify(jpa).save(any(ReconBreakEntity.class));

        // update path: the lifecycle columns move, history stays
        when(jpa.findById("brk_01")).thenReturn(Optional.of(ReconBreakEntity.fromDomain(break_)));
        break_.startInvestigation("ops.alice", "hypothesis", T1);
        adapter.save(break_);
        verify(jpa, times(2)).save(any(ReconBreakEntity.class));

        when(jpa.findById("brk_01")).thenReturn(Optional.of(ReconBreakEntity.fromDomain(break_)));
        assertThat(adapter.findById("brk_01")).isPresent();
        assertThat(adapter.findById("brk_01").get().state()).isEqualTo(BreakState.INVESTIGATING);
        assertThat(adapter.findById("brk_x")).isEmpty();

        // list-by-run / list-by-state / list-active / list-by-provider
        ReconBreakEntity entity = ReconBreakEntity.fromDomain(break_);
        when(jpa.findByRunIdOrderByDetectedAtAscIdAsc("run_01")).thenReturn(List.of(entity));
        assertThat(adapter.listByRun("run_01")).hasSize(1);

        when(jpa.findByStateOrderByDetectedAtAscIdAsc("investigating")).thenReturn(List.of(entity));
        assertThat(adapter.listByState(BreakState.INVESTIGATING)).hasSize(1);

        when(jpa.findByStatesOrderByDetectedAtAscIdAsc(
                List.of("open", "investigating"))).thenReturn(List.of(entity));
        assertThat(adapter.listActive()).hasSize(1);

        when(jpa.findByProviderOrderByDetectedAtAscIdAsc("honeycoin")).thenReturn(List.of(entity));
        assertThat(adapter.listByProvider("honeycoin")).hasSize(1);
        assertThat(adapter.listByProvider("honeycoin").get(0).providerAmount())
                .isEqualTo(Money.of(150_000, "KES"));
    }

    @Test
    void compensationAdapterDelegatesAndUpserts() {
        CompensationEntryJpaRepository jpa = Mockito.mock(CompensationEntryJpaRepository.class);
        JpaCompensationEntryRepository adapter = new JpaCompensationEntryRepository(jpa);

        List<CompensationLeg> legs = List.of(
                new CompensationLeg("suspense:recon:KES", PostingDirection.DEBIT,
                        Money.of(500, "KES")),
                new CompensationLeg("honeycoin:settlement:KES", PostingDirection.CREDIT,
                        Money.of(500, "KES")));
        CompensationEntry entry = CompensationEntry.propose("cmp_01",
                "brk_0123456789abcdef0123456789abcdef", "honeycoin",
                "ops:adj:brk_0123456789abcdef0123456789abcdef", "ops.alice", "variance", legs,
                null);

        when(jpa.findById("cmp_01")).thenReturn(Optional.empty());
        when(jpa.save(any(CompensationEntryEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        adapter.save(entry);
        verify(jpa).save(any(CompensationEntryEntity.class));

        // execution updates in place
        when(jpa.findById("cmp_01")).thenReturn(Optional.of(CompensationEntryEntity.fromDomain(
                entry)));
        entry.execute("ops.bob", UUID.randomUUID(), false, T1);
        adapter.save(entry);
        verify(jpa, times(2)).save(any(CompensationEntryEntity.class));

        when(jpa.findById("cmp_01")).thenReturn(Optional.of(CompensationEntryEntity.fromDomain(
                entry)));
        assertThat(adapter.findById("cmp_01")).isPresent();
        assertThat(adapter.findById("cmp_01").get().approver()).isEqualTo("ops.bob");
        assertThat(adapter.findById("cmp_x")).isEmpty();

        when(jpa.findByBreakIdOrderByCompensationKeyAsc(
                "brk_0123456789abcdef0123456789abcdef"))
                .thenReturn(List.of(CompensationEntryEntity.fromDomain(entry)));
        assertThat(adapter.listByBreak("brk_0123456789abcdef0123456789abcdef")).hasSize(1);

        when(jpa.countByBreakId("brk_0123456789abcdef0123456789abcdef")).thenReturn(1L);
        assertThat(adapter.countByBreak("brk_0123456789abcdef0123456789abcdef")).isEqualTo(1L);
    }

    @Test
    void settlementReportAdapterDelegatesAndMaps() {
        SettlementReportJpaRepository jpa = Mockito.mock(SettlementReportJpaRepository.class);
        JpaSettlementReportRepository adapter = new JpaSettlementReportRepository(jpa);

        SettlementReport report = SettlementReport.rehydrate("str_01", "run_01", "honeycoin",
                new com.sharkpay.reconciliation.domain.ReconWindow(FROM, TO), T1,
                List.of(new SettlementReport.CurrencyLine("KES", 1, 150_000L, 500L, 1, 150_000L,
                        500L, 1)),
                new SettlementReport.BreakSummary(0, 0, 0, 0, 0, 0));

        when(jpa.save(any(SettlementReportEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        adapter.save(report);
        verify(jpa).save(any(SettlementReportEntity.class));

        SettlementReportEntity entity = SettlementReportEntity.fromDomain(report);
        when(jpa.findFirstByRunId("run_01")).thenReturn(Optional.of(entity));
        assertThat(adapter.findByRunId("run_01")).hasValueSatisfying(found ->
                assertThat(found).usingRecursiveComparison().isEqualTo(report));

        when(jpa.findFirstByProviderAndWindowFromAndWindowToOrderByGeneratedAtDesc("honeycoin",
                FROM, TO)).thenReturn(Optional.of(entity));
        assertThat(adapter.findByProviderAndWindow("honeycoin", FROM, TO)).hasValueSatisfying(
                found -> assertThat(found).usingRecursiveComparison().isEqualTo(report));

        when(jpa.findByProviderOrderByGeneratedAtDescIdDesc("honeycoin"))
                .thenReturn(List.of(entity));
        assertThat(adapter.listByProvider("honeycoin")).hasSize(1);

        when(jpa.findFirstByRunId("run_x")).thenReturn(Optional.empty());
        assertThat(adapter.findByRunId("run_x")).isEmpty();
    }

    @Test
    void idempotencyAdapterDelegatesAndSwallowsInsertRaces() {
        IdempotencyKeyJpaRepository jpa = Mockito.mock(IdempotencyKeyJpaRepository.class);
        JpaIdempotencyStore adapter = new JpaIdempotencyStore(jpa);
        IdempotencyStore.StoredRequest request = new IdempotencyStore.StoredRequest(
                "TRIGGER_RUN|honeycoin|from|to", "run_01");

        when(jpa.findById(new IdempotencyKeyPk("TRIGGER_RUN", "key-1")))
                .thenReturn(Optional.empty());
        when(jpa.saveAndFlush(any(IdempotencyKeyEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThat(adapter.find(IdempotencyStore.Scope.TRIGGER_RUN, "key-1")).isEmpty();
        adapter.put(IdempotencyStore.Scope.TRIGGER_RUN, "key-1", request);

        when(jpa.findById(new IdempotencyKeyPk("TRIGGER_RUN", "key-1")))
                .thenReturn(Optional.of(new IdempotencyKeyEntity(
                        new IdempotencyKeyPk("TRIGGER_RUN", "key-1"),
                        request.requestFingerprint(), request.entityId(), T0)));
        assertThat(adapter.find(IdempotencyStore.Scope.TRIGGER_RUN, "key-1")).contains(request);

        // a concurrent duplicate insert is swallowed (the loser replays the
        // winner's record on its next attempt)
        when(jpa.saveAndFlush(any(IdempotencyKeyEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        assertThatCode(() -> adapter.put(IdempotencyStore.Scope.TRIGGER_RUN, "key-1", request))
                .doesNotThrowAnyException();

        adapter.remove(IdempotencyStore.Scope.TRIGGER_RUN, "key-1");
        verify(jpa).deleteById(new IdempotencyKeyPk("TRIGGER_RUN", "key-1"));
    }

    @Test
    void runAdapterNeverInsertsTwiceForTheSameId() {
        ReconRunJpaRepository jpa = Mockito.mock(ReconRunJpaRepository.class);
        JpaReconRunRepository adapter = new JpaReconRunRepository(jpa);
        ReconRun run = ReconRun.start("run_01", "honeycoin",
                new com.sharkpay.reconciliation.domain.ReconWindow(FROM, TO), T0);

        // the persisted row the adapter must update in place
        ReconRunEntity existing = ReconRunEntity.fromDomain(run);
        when(jpa.findById("run_01")).thenReturn(Optional.of(existing));
        when(jpa.save(any(ReconRunEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        adapter.save(run);
        adapter.save(run);
        // two saves, but both are UPDATES of the same persisted row (the
        // insert path — fromDomain — never ran: no fresh entity instance)
        org.mockito.ArgumentCaptor<ReconRunEntity> saved =
                org.mockito.ArgumentCaptor.forClass(ReconRunEntity.class);
        verify(jpa, times(2)).save(saved.capture());
        assertThat(saved.getAllValues().get(0)).isSameAs(existing);
        assertThat(saved.getAllValues().get(1)).isSameAs(existing);
        assertThat(saved.getAllValues().get(1).getId()).isEqualTo("run_01");
    }
}
