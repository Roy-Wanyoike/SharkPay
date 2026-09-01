package com.sharkpay.risk.storage;

import com.sharkpay.money.Money;
import com.sharkpay.risk.domain.Case;
import com.sharkpay.risk.domain.CaseResolution;
import com.sharkpay.risk.domain.CaseStatus;
import com.sharkpay.risk.domain.Channel;
import com.sharkpay.risk.domain.Decision;
import com.sharkpay.risk.domain.Evaluation;
import com.sharkpay.risk.domain.EvaluationRequest;
import com.sharkpay.risk.domain.KycTier;
import com.sharkpay.risk.domain.Outcome;
import com.sharkpay.risk.domain.PrincipalType;
import com.sharkpay.risk.domain.RuleResult;
import com.sharkpay.risk.domain.RuleSetConfig;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the JPA port adapters by mocking the Spring Data interfaces: no
 * database, no Spring context — pure delegation + mapping verification
 * (mirrors identity's JpaAdaptersTest).
 */
class RiskJpaAdaptersTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final Clock CLOCK = Clock.fixed(T0, ZoneOffset.UTC);

    private static Case transitionedCase() {
        Case c = Case.open(UUID.randomUUID(), "subject-1", "velocity spike", T0);
        c.transitionTo(CaseStatus.UNDER_REVIEW, "op-1", null, T0.plusSeconds(3600));
        c.transitionTo(CaseStatus.CLOSED, "op-2", CaseResolution.CLEARED, T0.plusSeconds(7200));
        return c;
    }

    private static Evaluation evaluation() {
        EvaluationRequest request = EvaluationRequest.of("1b9f2f4e-8c26-4a9e-9a3f-3f3b2a1c0d00",
                "subject-1", PrincipalType.INDIVIDUAL, KycTier.LIMITED, Money.of(100_00L, "KES"),
                Channel.PAYMENT);
        return new Evaluation(request.evaluationId(), request, Decision.ALLOW,
                List.of(new RuleResult("velocity_window", Outcome.PASS, "velocity ok: 0/10")), T0);
    }

    @Test
    void caseAdapterSavesRowAndAppendsWithIdempotency() {
        CaseJpaRepository cases = Mockito.mock(CaseJpaRepository.class);
        CaseTransitionJpaRepository transitions = Mockito.mock(CaseTransitionJpaRepository.class);
        CaseRepositoryAdapter adapter = new CaseRepositoryAdapter(cases, transitions);
        Case c = transitionedCase();

        when(cases.save(any(CaseEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(transitions.existsById(any(UUID.class))).thenReturn(false);
        when(transitions.save(any(CaseTransitionEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(adapter.save(c)).isSameAs(c);
        ArgumentCaptor<CaseEntity> caseCaptor = ArgumentCaptor.forClass(CaseEntity.class);
        verify(cases).save(caseCaptor.capture());
        assertThat(caseCaptor.getValue().id).isEqualTo(c.id());
        assertThat(caseCaptor.getValue().status).isEqualTo(CaseStatus.CLOSED);

        ArgumentCaptor<CaseTransitionEntity> transitionCaptor =
                ArgumentCaptor.forClass(CaseTransitionEntity.class);
        verify(transitions, Mockito.times(2)).save(transitionCaptor.capture());
        assertThat(transitionCaptor.getAllValues()).hasSize(2);
        assertThat(transitionCaptor.getAllValues().get(1).toStatus).isEqualTo(CaseStatus.CLOSED);
        assertThat(transitionCaptor.getAllValues().get(1).resolution).isEqualTo(CaseResolution.CLEARED);

        // a re-save of already-persisted transitions appends nothing
        Mockito.reset(transitions);
        when(transitions.existsById(any(UUID.class))).thenReturn(true);
        adapter.save(c);
        verify(transitions, never()).save(any(CaseTransitionEntity.class));
    }

    @Test
    void caseAdapterFindsAndRehydratesTheAggregate() {
        CaseJpaRepository cases = Mockito.mock(CaseJpaRepository.class);
        CaseTransitionJpaRepository transitions = Mockito.mock(CaseTransitionJpaRepository.class);
        CaseRepositoryAdapter adapter = new CaseRepositoryAdapter(cases, transitions);
        Case c = transitionedCase();

        when(cases.findById(c.id())).thenReturn(Optional.of(CaseMapper.toEntity(c)));
        when(transitions.findByCaseIdOrderByOccurredAtAsc(c.id()))
                .thenReturn(CaseMapper.toTransitionEntities(c));

        Optional<Case> found = adapter.findById(c.id());
        assertThat(found).isPresent();
        assertThat(found.get().publicId()).isEqualTo(c.publicId());
        assertThat(found.get().status()).isEqualTo(CaseStatus.CLOSED);
        assertThat(found.get().transitions()).hasSize(2);
        assertThat(found.get().transitions().get(0).actor()).isEqualTo("op-1");

        when(cases.findById(UUID.randomUUID())).thenReturn(Optional.empty());
        assertThat(adapter.findById(UUID.randomUUID())).isEmpty();
    }

    @Test
    void evaluationAdapterSavesAndFindsByIdempotencyKey() {
        EvaluationJpaRepository repo = Mockito.mock(EvaluationJpaRepository.class);
        EvaluationRepositoryAdapter adapter = new EvaluationRepositoryAdapter(repo);
        Evaluation evaluation = evaluation();

        when(repo.save(any(EvaluationEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        adapter.save(evaluation);
        ArgumentCaptor<EvaluationEntity> captor = ArgumentCaptor.forClass(EvaluationEntity.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().id)
                .isEqualTo(UUID.fromString("1b9f2f4e-8c26-4a9e-9a3f-3f3b2a1c0d00"));
        assertThat(captor.getValue().decision).isEqualTo("allow");

        when(repo.findById(UUID.fromString("1b9f2f4e-8c26-4a9e-9a3f-3f3b2a1c0d00")))
                .thenReturn(Optional.of(EvaluationMapper.toEntity(evaluation)));
        assertThat(adapter.findById("1b9f2f4e-8c26-4a9e-9a3f-3f3b2a1c0d00")).contains(evaluation);

        when(repo.findById(UUID.randomUUID())).thenReturn(Optional.empty());
        assertThat(adapter.findById(UUID.randomUUID().toString())).isEmpty();
    }

    @Test
    void ruleSetAdapterReturnsTheActiveRowAndFallsBackToDefaults() {
        RuleSetJpaRepository repo = Mockito.mock(RuleSetJpaRepository.class);
        RuleSetRepositoryAdapter adapter = new RuleSetRepositoryAdapter(repo);

        assertThat(adapter.activeRuleSet()).isEqualTo(RuleSetConfig.defaults());

        RuleSetConfig custom = customConfig();
        when(repo.findFirstByActiveTrueOrderByVersionDesc())
                .thenReturn(Optional.of(new RuleSetEntity(3L, 7L, RuleSetMapper.toJson(custom), true)));
        assertThat(adapter.activeRuleSet()).isEqualTo(custom);
    }

    @Test
    void velocityAdapterCountsAndSumsWindowBuckets() {
        VelocityCounterJpaRepository repo = Mockito.mock(VelocityCounterJpaRepository.class);
        VelocityCounterStoreAdapter adapter = new VelocityCounterStoreAdapter(repo, CLOCK);

        when(repo.sumTxnCount(eq("subject-1"), anyList())).thenReturn(7L);
        when(repo.sumAmountMinor(eq("subject-1"), eq("KES"), anyList())).thenReturn(150_00L);
        when(repo.sumAmountMinor(eq("subject-1"), eq("USD"), anyList())).thenReturn(0L);

        assertThat(adapter.countInWindow("subject-1", Duration.ofHours(1))).isEqualTo(7);
        assertThat(adapter.amountInWindow("subject-1", "KES", Duration.ofHours(24)))
                .isEqualTo(Money.of(150_00L, "KES"));
        assertThat(adapter.amountInWindow("subject-1", "USD", Duration.ofDays(7)))
                .isEqualTo(Money.zero("USD"));

        // the window is derived from the adapter's clock, bucketed to minutes
        verify(repo).sumTxnCount("subject-1",
                VelocityBuckets.windowBucketIds(Duration.ofHours(1), T0));
        verify(repo).sumAmountMinor("subject-1", "KES",
                VelocityBuckets.windowBucketIds(Duration.ofHours(24), T0));
    }

    @Test
    void velocityAdapterRecordsIntoNewAndExistingBuckets() {
        VelocityCounterJpaRepository repo = Mockito.mock(VelocityCounterJpaRepository.class);
        VelocityCounterStoreAdapter adapter = new VelocityCounterStoreAdapter(repo, CLOCK);
        String bucket = VelocityBuckets.bucketId(T0);
        VelocityCounterId id = new VelocityCounterId("subject-1", bucket, "KES");

        when(repo.findById(id)).thenReturn(Optional.empty());
        when(repo.save(any(VelocityCounterEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        adapter.record("subject-1", Money.of(100_00L, "KES"), T0);
        ArgumentCaptor<VelocityCounterEntity> captor = ArgumentCaptor.forClass(VelocityCounterEntity.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().subject).isEqualTo("subject-1");
        assertThat(captor.getValue().windowBucket).isEqualTo(bucket);
        assertThat(captor.getValue().currency).isEqualTo("KES");
        assertThat(captor.getValue().txnCount).isEqualTo(1);
        assertThat(captor.getValue().amountMinor).isEqualTo(100_00L);

        // second record in the same bucket increments instead of inserting
        VelocityCounterEntity existing = captor.getValue();
        when(repo.findById(id)).thenReturn(Optional.of(existing));
        adapter.record("subject-1", Money.of(50_00L, "KES"), T0);

        ArgumentCaptor<VelocityCounterEntity> updated = ArgumentCaptor.forClass(VelocityCounterEntity.class);
        verify(repo, Mockito.times(2)).save(updated.capture());
        assertThat(updated.getAllValues().get(1).txnCount).isEqualTo(2);
        assertThat(updated.getAllValues().get(1).amountMinor).isEqualTo(150_00L);
    }

    private static RuleSetConfig customConfig() {
        return new RuleSetConfig("ops-2026-09", 7, true,
                new com.sharkpay.risk.domain.VelocityPolicy(5, Duration.ofMinutes(30)),
                new java.util.EnumMap<>(RuleSetConfig.defaults().tierLimits()),
                RuleSetConfig.defaults().agentLimits(),
                java.util.Set.of("KP"), java.util.Set.of("shark_bad"));
    }
}
