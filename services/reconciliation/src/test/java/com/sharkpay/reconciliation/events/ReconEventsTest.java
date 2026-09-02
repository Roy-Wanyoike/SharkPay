package com.sharkpay.reconciliation.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sharkpay.reconciliation.testsupport.ReconTestEnv;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G4 (ADR 003) contract evidence: every event the reconciliation service
 * publishes — run.completed, break.detected, break.escalated,
 * compensation.executed — is serialized (Jackson 3, snake_case, NON_NULL)
 * and structurally validated against the merged contract
 * contracts/events/recon.v1.json, with UUID v7 ids consumers dedupe on.
 */
class ReconEventsTest {

    private static final String CONTRACT = "recon.v1.json";

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
            .changeDefaultPropertyInclusion(value ->
                    value.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @Test
    void everyPublishedEventValidatesAgainstTheMergedContractSchema() {
        driveFullLifecycle();

        List<CloudEvent> published = env.events.events();
        // all four recon event types were produced by the lifecycle
        assertThat(published).isNotEmpty();
        assertThat(published.stream().map(CloudEvent::type).distinct())
                .containsExactlyInAnyOrder(ReconEvents.RUN_COMPLETED, ReconEvents.BREAK_DETECTED,
                        ReconEvents.BREAK_ESCALATED, ReconEvents.COMPENSATION_EXECUTED);

        JsonSchemaCheck check = JsonSchemaCheck.load(CONTRACT);
        for (CloudEvent event : published) {
            JsonNode tree = mapper.valueToTree(event);
            assertThat(check.errors(tree))
                    .as("event %s must validate against %s:\n%s", event.type(), CONTRACT, tree)
                    .isEmpty();
        }
    }

    @Test
    void everyEventCarriesAUniqueUuidV7IdAndTheFixedEnvelopeConstants() {
        driveFullLifecycle();

        List<String> ids = env.events.events().stream().map(CloudEvent::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
        for (String id : ids) {
            // UUID v7 (RFC 9562) — consumers dedupe on it
            assertThat(id)
                    .matches("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
        }
        for (CloudEvent event : env.events.events()) {
            assertThat(event.specversion()).isEqualTo("1.0");
            assertThat(event.source()).isEqualTo("sharkpay/reconciliation");
            assertThat(event.subject()).matches("^(run|brk|cmp)_[0-9A-Za-z]{20,}$");
            assertThat(event.type()).isEqualTo(event.type().toLowerCase());
        }
    }

    @Test
    void missingBreaksOmitTheAbsentSideNeverNullIt() {
        // a MISSING_INTERNAL break: provider side present, internal absent
        env.seedProviderLine("hc_ghost", "CONFIRMED", 2_000, 0);
        env.triggerDefault("key-1");

        CloudEvent event = env.events.eventsOfType(ReconEvents.BREAK_DETECTED).get(0);
        ObjectNode tree = (ObjectNode) mapper.valueToTree(event);
        ObjectNode data = (ObjectNode) tree.get("data");

        assertThat(data.get("break_type").asString()).isEqualTo("missing_internal");
        assertThat(data.get("provider_amount")).isNotNull();
        assertThat(data.has("internal_amount")).isFalse();
        assertThat(data.has("internal_ref")).isFalse();
        assertThat(data.has("internal_status")).isFalse();
        assertThat(data.has("internal_fee")).isFalse();
        // schema-clean: still validates with the absent side omitted
        assertThat(JsonSchemaCheck.load(CONTRACT).errors(tree)).isEmpty();
    }

    @Test
    void theRunCompletedPayloadCarriesTheWindowAndCounts() {
        env.seedMatch("hc_clean", 150_000, "KES", 500);
        env.seedProviderLine("hc_ghost", "CONFIRMED", 2_000, 0);
        var result = env.triggerDefault("key-1");

        CloudEvent event = env.events.eventsOfType(ReconEvents.RUN_COMPLETED).get(0);
        assertThat(event.subject()).isEqualTo(result.run().id());

        JsonNode data = mapper.valueToTree(event).get("data");
        assertThat(data.get("run_id").asString()).isEqualTo(result.run().id());
        assertThat(data.get("provider").asString()).isEqualTo("honeycoin");
        assertThat(data.get("window_from").asString()).isEqualTo("2026-09-01T00:00:00Z");
        assertThat(data.get("window_to").asString()).isEqualTo("2026-09-02T00:00:00Z");
        assertThat(data.get("provider_lines").asInt()).isEqualTo(2);
        assertThat(data.get("internal_lines").asInt()).isEqualTo(1);
        assertThat(data.get("matched_lines").asInt()).isEqualTo(1);
        assertThat(data.get("break_count").asInt()).isEqualTo(1);
    }

    @Test
    void theEscalatedPayloadCarriesTheBucketAndAge() {
        env.seedProviderLine("hc_ghost", "CONFIRMED", 2_000, 0);
        env.triggerDefault("key-1");
        env.clock.advance(Duration.ofHours(25));   // FRESH → AGING
        env.sweepAging.sweep();

        CloudEvent event = env.events.eventsOfType(ReconEvents.BREAK_ESCALATED).get(0);
        JsonNode data = mapper.valueToTree(event).get("data");
        assertThat(data.get("bucket").asString()).isEqualTo("aging");
        assertThat(data.get("age_hours").asLong()).isEqualTo(25);
        assertThat(data.get("state").asString()).isEqualTo("open");
        assertThat(data.get("break_type").asString()).isEqualTo("missing_internal");
    }

    @Test
    void theCompensationExecutedPayloadCarriesBothPrincipalsAndTheJournalLink() {
        String compensationId = seedCompensableBreak();
        env.approveAndExecute.approveAndExecute(compensationId, "ops.bob");

        CloudEvent event = env.events.eventsOfType(ReconEvents.COMPENSATION_EXECUTED).get(0);
        JsonNode data = mapper.valueToTree(event).get("data");
        assertThat(data.get("compensation_id").asString()).isEqualTo(compensationId);
        assertThat(data.get("break_id").asString()).isNotBlank();
        assertThat(data.get("provider").asString()).isEqualTo("honeycoin");
        assertThat(data.get("requester").asString()).isEqualTo("ops.alice");
        assertThat(data.get("approver").asString()).isEqualTo("ops.bob");
        assertThat(data.get("ledger_entry_id").asString()).matches(
                "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$");
        assertThat(data.get("compensation_key").asString()).startsWith("ops:adj:brk_");
        assertThat(event.subject()).isEqualTo(compensationId);
    }

    @Test
    void theValidatorActuallyRejectsContractViolations() {
        seedCompensableBreak();
        env.approveAndExecute.approveAndExecute(env.compensations.listByBreak(
                env.breaks.listActive().get(0).id()).get(0).id(), "ops.bob");
        env.clock.advance(Duration.ofHours(25));
        env.sweepAging.sweep();
        CloudEvent event = env.events.events().get(0);   // break.detected
        CloudEvent escalated = env.events.eventsOfType(ReconEvents.BREAK_ESCALATED).get(0);
        JsonSchemaCheck check = JsonSchemaCheck.load(CONTRACT);

        // an extra property is rejected (additionalProperties: false)
        ObjectNode extra = (ObjectNode) mapper.valueToTree(event);
        extra.put("mystery_field", "x");
        assertThat(check.errors(extra)).anyMatch(error -> error.contains("mystery_field"));

        // a broken envelope constant is rejected
        ObjectNode badSpec = (ObjectNode) mapper.valueToTree(event);
        badSpec.put("specversion", "0.3");
        assertThat(check.errors(badSpec)).anyMatch(error -> error.contains("0.3"));

        // a wrong source is rejected
        ObjectNode badSource = (ObjectNode) mapper.valueToTree(event);
        badSource.put("source", "sharkpay/payments");
        assertThat(check.errors(badSource)).anyMatch(error -> error.contains("sharkpay/payments"));

        // a non-uuid event id is rejected
        ObjectNode badId = (ObjectNode) mapper.valueToTree(event);
        badId.put("id", "not-a-uuid");
        assertThat(check.errors(badId)).anyMatch(error -> error.contains("not a uuid"));

        // an out-of-enum break type is rejected
        ObjectNode badType = (ObjectNode) mapper.valueToTree(event);
        ((ObjectNode) badType.get("data")).put("break_type", "weird_break");
        assertThat(check.errors(badType)).anyMatch(error -> error.contains("weird_break"));

        // a negative age is rejected (minimum: 0) — on the escalated payload
        ObjectNode negativeAge = (ObjectNode) mapper.valueToTree(escalated);
        ((ObjectNode) negativeAge.get("data")).put("age_hours", -1);
        assertThat(check.errors(negativeAge)).anyMatch(error -> error.contains("below minimum"));

        // a money field with a string amount is rejected
        ObjectNode badMoney = (ObjectNode) mapper.valueToTree(event);
        ((ObjectNode) ((ObjectNode) badMoney.get("data")).get("provider_amount"))
                .put("amount_minor", "500");
        assertThat(check.errors(badMoney)).anyMatch(error -> error.contains("type"));
    }

    @Test
    void onlyCatalogEventTypesArePublished() {
        // the append-only registry: exactly the four recon topics, nothing
        // else leaves the service
        driveFullLifecycle();
        assertThat(env.events.events())
                .allSatisfy(event -> assertThat(event.type()).isIn(
                        ReconEvents.RUN_COMPLETED, ReconEvents.BREAK_DETECTED,
                        ReconEvents.BREAK_ESCALATED, ReconEvents.COMPENSATION_EXECUTED));
    }

    private final ReconTestEnv env = new ReconTestEnv();

    /** trigger (breaks detected) → investigate → age 25 h + sweep → compensate. */
    private void driveFullLifecycle() {
        String compensationId = seedCompensableBreak();
        // investigate + age + escalate the first break
        env.transitionBreak.transition(env.breaks.listActive().get(0).id(), "investigating",
                "ops.alice", "hypothesis: settlement file late");
        env.clock.advance(Duration.ofHours(25));
        env.sweepAging.sweep();          // AGING transition → break.escalated
        env.clock.advance(Duration.ofHours(48));
        env.sweepAging.sweep();          // STALE transition → break.escalated
        // execute the 4-eyes compensation
        env.approveAndExecute.approveAndExecute(compensationId, "ops.bob");
    }

    /** Seeds a run with two breaks and proposes a compensation for the first. */
    private String seedCompensableBreak() {
        env.seedProviderLine("hc_amount", "CONFIRMED", 150_000, 500);
        env.seedInternalLine("int_amount", "hc_amount", "CONFIRMED", 149_500, 500);
        env.seedProviderLine("hc_ghost", "CONFIRMED", 2_000, 0);
        String breakId = env.triggerDefault("key-run").breaks().get(0).id();
        return env.proposeCompensation.propose("key-prop", breakId, "ops.alice",
                "settlement variance", legs(), null).entry().id();
    }

    private static List<com.sharkpay.reconciliation.domain.CompensationLeg> legs() {
        return List.of(
                new com.sharkpay.reconciliation.domain.CompensationLeg("suspense:recon:KES",
                        com.sharkpay.reconciliation.domain.PostingDirection.DEBIT,
                        com.sharkpay.money.Money.of(500, "KES")),
                new com.sharkpay.reconciliation.domain.CompensationLeg(
                        "honeycoin:settlement:KES",
                        com.sharkpay.reconciliation.domain.PostingDirection.CREDIT,
                        com.sharkpay.money.Money.of(500, "KES")));
    }
}
