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
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Domain ⇄ entity round-trips for every table: the full column set maps
 * both ways (money slots all-three-or-none on absent sides, JSON columns
 * via StorageJson, lifecycle columns via applyDomain), so the JPA adapters
 * rehydrate exactly what the domain persisted.
 */
class EntityRoundTripTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-01T11:00:00Z");
    private static final Instant T2 = Instant.parse("2026-09-04T12:00:00Z");
    private static final java.time.Instant FROM = Instant.parse("2026-09-01T00:00:00Z");
    private static final java.time.Instant TO = Instant.parse("2026-09-02T00:00:00Z");

    @Test
    void runEntityRoundTripsEveryColumn() {
        ReconRun run = ReconRun.rehydrate("run_01", "honeycoin",
                new com.sharkpay.reconciliation.domain.ReconWindow(FROM, TO), T0,
                ReconRunState.COMPLETED, T1, null, 12, 10, 9, 3);
        ReconRun roundTripped = ReconRunEntity.fromDomain(run).toDomain();
        assertThat(roundTripped).usingRecursiveComparison().isEqualTo(run);

        // the failed shape round-trips too (reason present, counts zero)
        ReconRun failed = ReconRun.rehydrate("run_02", "honeycoin",
                new com.sharkpay.reconciliation.domain.ReconWindow(FROM, TO), T0,
                ReconRunState.FAILED, T1, "provider statement unavailable", 0, 0, 0, 0);
        assertThat(ReconRunEntity.fromDomain(failed).toDomain())
                .usingRecursiveComparison().isEqualTo(failed);

        // applyDomain moves the mutable columns of a persisted row of the
        // SAME run (identity columns stay untouched — updates are in place)
        ReconRunEntity entity = ReconRunEntity.fromDomain(ReconRun.start("run_01", "honeycoin",
                new com.sharkpay.reconciliation.domain.ReconWindow(FROM, TO), T0));
        entity.applyDomain(run);
        assertThat(entity.toDomain()).usingRecursiveComparison().isEqualTo(run);

        // the JPA no-arg constructor exists for the provider
        assertThat(new ReconRunEntity()).isNotNull();
    }

    @Test
    void breakEntityRoundTripsBothSideFactsAndLifecycle() {
        // an AMOUNT_MISMATCH break: both sides present
        DetectedBreak amountDetected = new DetectedBreak(BreakType.AMOUNT_MISMATCH, "hc_1",
                "int_1", Money.of(150_000, "KES"), Money.of(149_500, "KES"), Money.of(500, "KES"),
                Money.of(500, "KES"), "CONFIRMED", "CONFIRMED");
        ReconBreak bothSides = ReconBreak.detect("brk_01", "run_01", "honeycoin", amountDetected,
                T0);
        assertThat(ReconBreakEntity.fromDomain(bothSides).toDomain())
                .usingRecursiveComparison().ignoringFieldsMatchingRegexes("note", "lastActor",
                        "lastTransitionAt", "compensationId", "resolvedAt", "escalatedAt")
                .isEqualTo(bothSides);
        // note: ReconBreak has no equals — compare the money slots explicitly
        ReconBreak restored = ReconBreakEntity.fromDomain(bothSides).toDomain();
        assertThat(restored.providerAmount()).isEqualTo(Money.of(150_000, "KES"));
        assertThat(restored.internalAmount()).isEqualTo(Money.of(149_500, "KES"));
        assertThat(restored.providerFee()).isEqualTo(Money.of(500, "KES"));
        assertThat(restored.internalFee()).isEqualTo(Money.of(500, "KES"));
        assertThat(restored.providerStatus()).isEqualTo("CONFIRMED");
        assertThat(restored.internalStatus()).isEqualTo("CONFIRMED");
        assertThat(restored.detectedAt()).isEqualTo(T0);
        assertThat(restored.state()).isEqualTo(BreakState.OPEN);

        // a MISSING_INTERNAL break: the internal side is absent (NULL triple)
        DetectedBreak missingDetected = new DetectedBreak(BreakType.MISSING_INTERNAL, "hc_2", null,
                Money.of(2_000, "KES"), null, Money.of(0, "KES"), null, "CONFIRMED", null);
        ReconBreak missingInternal = ReconBreak.detect("brk_02", "run_01", "honeycoin",
                missingDetected, T0);
        ReconBreak restoredMissing = ReconBreakEntity.fromDomain(missingInternal).toDomain();
        assertThat(restoredMissing.internalAmount()).isNull();
        assertThat(restoredMissing.internalFee()).isNull();
        assertThat(restoredMissing.internalStatus()).isNull();
        assertThat(restoredMissing.providerAmount()).isEqualTo(Money.of(2_000, "KES"));

        // applyDomain persists a full lifecycle transition (audit columns)
        ReconBreakEntity entity = ReconBreakEntity.fromDomain(bothSides);
        bothSides.startInvestigation("ops.alice", "hypothesis", T1);
        bothSides.markCompensated("cmp_01", T1);
        entity.applyDomain(bothSides);
        ReconBreak compensated = entity.toDomain();
        assertThat(compensated.state()).isEqualTo(BreakState.COMPENSATED);
        assertThat(compensated.compensationId()).isEqualTo("cmp_01");
        assertThat(compensated.lastActor()).isEqualTo("ops.alice");
        assertThat(compensated.note()).isEqualTo("hypothesis");
        assertThat(compensated.resolvedAt()).isEqualTo(T1);

        // escalated columns round-trip through rehydrate-style storage
        ReconBreak escalated = new ReconBreak.Builder("brk_03", "run_01", "honeycoin",
                BreakType.FEE_MISMATCH)
                .providerRef("hc_3").internalRef("int_3")
                .providerAmount(Money.of(1, "KES")).internalAmount(Money.of(1, "KES"))
                .providerFee(Money.of(7, "KES")).internalFee(Money.of(5, "KES"))
                .providerStatus("CONFIRMED").internalStatus("CONFIRMED")
                .detectedAt(T0).state(BreakState.INVESTIGATING)
                .bucket(com.sharkpay.reconciliation.domain.AgingBucket.STALE)
                .note("aging").lastActor("ops.bob").lastTransitionAt(T1)
                .resolvedAt(null).escalatedAt(T2)
                .build();
        assertThat(ReconBreakEntity.fromDomain(escalated).toDomain()).usingRecursiveComparison()
                .isEqualTo(escalated);
    }

    @Test
    void compensationEntityRoundTripsLegsAndTheExecutedShape() {
        List<CompensationLeg> legs = List.of(
                new CompensationLeg("suspense:recon:KES", PostingDirection.DEBIT,
                        Money.of(1_500, "KES")),
                new CompensationLeg("honeycoin:settlement:KES", PostingDirection.CREDIT,
                        Money.of(1_500, "KES")),
                new CompensationLeg("suspense:recon:USD", PostingDirection.DEBIT,
                        Money.of(75, "USD")),
                new CompensationLeg("honeycoin:settlement:USD", PostingDirection.CREDIT,
                        Money.of(75, "USD")));

        UUID reverses = UUID.randomUUID();
        UUID ledgerEntry = UUID.randomUUID();
        CompensationEntry executed = CompensationEntry.rehydrate("cmp_01",
                "brk_0123456789abcdef0123456789abcdef", "honeycoin",
                "ops:adj:brk_0123456789abcdef0123456789abcdef", "ops.alice",
                "settlement variance", legs, reverses,
                CompensationEntry.CompensationState.EXECUTED, "ops.bob", ledgerEntry, T1, true);

        CompensationEntry restored = CompensationEntryEntity.fromDomain(executed).toDomain();
        assertThat(restored.id()).isEqualTo("cmp_01");
        assertThat(restored.breakId()).isEqualTo("brk_0123456789abcdef0123456789abcdef");
        assertThat(restored.compensationKey()).isEqualTo(
                "ops:adj:brk_0123456789abcdef0123456789abcdef");
        assertThat(restored.requester()).isEqualTo("ops.alice");
        assertThat(restored.approver()).isEqualTo("ops.bob");
        assertThat(restored.reason()).isEqualTo("settlement variance");
        assertThat(restored.legs()).containsExactlyElementsOf(legs);
        assertThat(restored.reversesEntryId()).isEqualTo(reverses);
        assertThat(restored.state()).isEqualTo(CompensationEntry.CompensationState.EXECUTED);
        assertThat(restored.ledgerEntryId()).isEqualTo(ledgerEntry);
        assertThat(restored.executedAt()).isEqualTo(T1);
        assertThat(restored.ledgerReplay()).isTrue();

        // applyDomain persists the execution columns of a PROPOSED row
        CompensationEntry proposed = CompensationEntry.propose("cmp_02",
                "brk_0123456789abcdef0123456789abcdee", "honeycoin",
                "ops:adj:brk_0123456789abcdef0123456789abcdee", "ops.alice", "r", legs, null);
        CompensationEntryEntity entity = CompensationEntryEntity.fromDomain(proposed);
        proposed.execute("ops.bob", ledgerEntry, false, T1);
        entity.applyDomain(proposed);
        CompensationEntry afterExecution = entity.toDomain();
        assertThat(afterExecution.state()).isEqualTo(CompensationEntry.CompensationState.EXECUTED);
        assertThat(afterExecution.approver()).isEqualTo("ops.bob");

        // the JPA no-arg constructors exist for the provider
        assertThat(new CompensationEntryEntity()).isNotNull();
        assertThat(new SettlementReportEntity()).isNotNull();
        assertThat(new IdempotencyKeyEntity()).isNotNull();
    }

    @Test
    void settlementReportEntityRoundTripsCurrencyLinesAndBreakSummary() {
        SettlementReport report = SettlementReport.rehydrate("str_01", "run_01", "honeycoin",
                new com.sharkpay.reconciliation.domain.ReconWindow(FROM, TO), T1,
                List.of(new SettlementReport.CurrencyLine("KES", 2, 200_000L, 750L, 1, 150_000L,
                                500L, 1),
                        new SettlementReport.CurrencyLine("USD", 1, 100L, 0L, 0, 0L, 0L, 0)),
                new SettlementReport.BreakSummary(1, 1, 1, 1, 1, 1));

        SettlementReport restored = SettlementReportEntity.fromDomain(report).toDomain();
        assertThat(restored.id()).isEqualTo("str_01");
        assertThat(restored.runId()).isEqualTo("run_01");
        assertThat(restored.currencyLines()).containsExactlyElementsOf(report.currencyLines());
        assertThat(restored.breakSummary()).isEqualTo(report.breakSummary());
        assertThat(restored.generatedAt()).isEqualTo(T1);
    }

    @Test
    void idempotencyKeyEntityCarriesTheCompositeKeyFields() {
        IdempotencyKeyPk pk = new IdempotencyKeyPk("TRIGGER_RUN", "key-1");
        IdempotencyKeyEntity entity = new IdempotencyKeyEntity(pk, "TRIGGER_RUN|x", "run_01", T0);

        assertThat(entity.getScope()).isEqualTo("TRIGGER_RUN");
        assertThat(entity.getIdempotencyKey()).isEqualTo("key-1");
        assertThat(entity.getRequestFingerprint()).isEqualTo("TRIGGER_RUN|x");
        assertThat(entity.getEntityId()).isEqualTo("run_01");
        assertThat(entity.getCreatedAt()).isEqualTo(T0);

        // composite key equality (the (scope, key) uniqueness)
        assertThat(new IdempotencyKeyPk("TRIGGER_RUN", "key-1")).isEqualTo(pk);
        assertThat(new IdempotencyKeyPk("TRIGGER_RUN", "key-1"))
                .hasSameHashCodeAs(pk);
        assertThat(new IdempotencyKeyPk("TRIGGER_RUN", "key-2")).isNotEqualTo(pk);
        assertThat(new IdempotencyKeyPk("PROPOSE_COMPENSATION", "key-1")).isNotEqualTo(pk);
        assertThat(new IdempotencyKeyPk()).isNotEqualTo(pk);
        assertThat(new IdempotencyKeyPk()).isEqualTo(new IdempotencyKeyPk());
        assertThat(new IdempotencyKeyPk("a", "b").getScope()).isEqualTo("a");
        assertThat(new IdempotencyKeyPk("a", "b").getIdempotencyKey()).isEqualTo("b");
    }

    @Test
    void storageJsonRoundTripsLegsAndCurrencyLinesExactly() {
        List<CompensationLeg> legs = List.of(
                new CompensationLeg("suspense:recon:KES", PostingDirection.DEBIT,
                        Money.of(1_500, "KES")),
                new CompensationLeg("honeycoin:settlement:KES", PostingDirection.CREDIT,
                        Money.of(1_500, "KES")),
                new CompensationLeg("suspense:recon:USDC", PostingDirection.DEBIT,
                        Money.of(9_000_000_000L, "USDC")),
                new CompensationLeg("honeycoin:settlement:USDC", PostingDirection.CREDIT,
                        Money.of(9_000_000_000L, "USDC")));
        String json = StorageJson.writeLegs(legs);
        assertThat(StorageJson.readLegs(json)).containsExactlyElementsOf(legs);

        List<SettlementReport.CurrencyLine> lines = List.of(
                new SettlementReport.CurrencyLine("KES", 2, 200_000L, 750L, 1, 150_000L, 500L, 1),
                new SettlementReport.CurrencyLine("USD", 1, Long.MAX_VALUE / 2, 0L, 0, 0L, 0L, 0));
        assertThat(StorageJson.readCurrencyLines(StorageJson.writeCurrencyLines(lines)))
                .containsExactlyElementsOf(lines);

        // blank/null columns read as empty lists, never null / never throw
        assertThat(StorageJson.readLegs(null)).isEmpty();
        assertThat(StorageJson.readLegs("  ")).isEmpty();
        assertThat(StorageJson.readCurrencyLines(null)).isEmpty();
        assertThat(StorageJson.readCurrencyLines("")).isEmpty();

        // the JSON is integer minor units only — never a float
        assertThat(json).contains("\"amount_minor\":1500").doesNotContain("1500.0");
    }
}
