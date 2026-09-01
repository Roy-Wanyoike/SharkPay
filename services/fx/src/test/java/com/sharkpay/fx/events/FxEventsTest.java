package com.sharkpay.fx.events;

import com.sharkpay.fx.testsupport.FxTestEnv;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G4 (ADR 003) contract evidence: every event the FX service publishes is
 * serialized (Jackson 3, snake_case envelope) and structurally validated
 * against the merged schema contracts/events/fx.v1.json — plus the event
 * catalog conventions (uuid v7 ids, one event per transition).
 */
class FxEventsTest {

    private static final String FX_EVENTS_SCHEMA = "fx.v1.json";

    private final FxTestEnv env = new FxTestEnv();

    /** snake_case envelope naming (occurredAt &#8594; occurred_at). */
    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
            .build();

    @Test
    void everyPublishedEventValidatesAgainstTheMergedContractSchema() {
        driveLifecycle();
        List<CloudEvent> published = env.events.events();
        assertThat(published).isNotEmpty();
        for (CloudEvent event : published) {
            JsonSchemaCheck check = JsonSchemaCheck.load(FX_EVENTS_SCHEMA);
            JsonNode tree = mapper.valueToTree(event);
            assertThat(check.errors(tree))
                    .as("event %s must validate against %s:\n%s", event.type(),
                            FX_EVENTS_SCHEMA, tree)
                    .isEmpty();
        }
    }

    @Test
    void everyEventCarriesAUniqueUuidV7Id() {
        driveLifecycle();
        List<String> ids = env.events.events().stream().map(CloudEvent::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
        for (String id : ids) {
            // UUID v7 format per the event catalog convention (consumers
            // dedupe on it, time-ordered)
            assertThat(id).matches("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
        }
    }

    @Test
    void exactlyOneEventPerLifecycleTransition() {
        driveLifecycle();
        assertThat(env.events.eventsOfType(FxEvents.QUOTE_LOCKED)).hasSize(1);
        assertThat(env.events.eventsOfType(FxEvents.CONVERSION_EXECUTED)).hasSize(1);
        // re-locking an already-locked quote must not re-publish
        com.sharkpay.fx.domain.Quote second = env.newQuote("EUR", "KES", 5_000);
        env.lockQuote.lock(second.id());
        env.lockQuote.lock(second.id());
        assertThat(env.events.eventsOfType(FxEvents.QUOTE_LOCKED)).hasSize(2);
    }

    @Test
    void subjectCarriesThePublicIdOfTheAffectedResource() {
        driveLifecycle();
        assertThat(env.events.eventsOfType(FxEvents.QUOTE_LOCKED))
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.subject()).startsWith("fxq_"));
        assertThat(env.events.eventsOfType(FxEvents.CONVERSION_EXECUTED))
                .isNotEmpty()
                .allSatisfy(event -> assertThat(event.subject()).startsWith("cnv_"));
    }

    private void driveLifecycle() {
        com.sharkpay.fx.domain.Quote quote = env.newLockedQuote("USD", "KES", 10_000);
        env.convert.convert("events-lifecycle-1", quote.id(), "wallet/src-USD", "wallet/dst-KES");
    }
}
