package com.sharkpay.wallet.events;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.sharkpay.money.Money;
import com.sharkpay.wallet.domain.Source;
import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.testsupport.WalletTestEnv;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G4 (ADR 003) contract evidence: every event the wallet service publishes
 * is serialized (Jackson 3, snake_case, NON_NULL) and structurally validated
 * against the merged schemas in contracts/events/ —
 * wallet.holds.v1.json, wallet.state.v1.json and wallet.v1.json.
 */
class WalletEventsTest {

    private final WalletTestEnv env = new WalletTestEnv();

    private final JsonMapper mapper = JsonMapper.builder()
            .propertyNamingStrategy(tools.jackson.databind.PropertyNamingStrategies.SNAKE_CASE)
            .changeDefaultPropertyInclusion(value ->
                    value.withValueInclusion(JsonInclude.Include.NON_NULL))
            .build();

    @Test
    void everyPublishedEventValidatesAgainstItsMergedContractSchema() {
        driveFullLifecycle();

        List<CloudEvent> published = env.events.events();
        assertThat(published).isNotEmpty();
        for (CloudEvent event : published) {
            JsonSchemaCheck check = JsonSchemaCheck.load(schemaFileOf(event.type()));
            JsonNode tree = mapper.valueToTree(event);
            assertThat(check.errors(tree))
                    .as("event %s must validate against %s:\n%s", event.type(),
                            schemaFileOf(event.type()), tree)
                    .isEmpty();
        }
    }

    @Test
    void everyEventCarriesAUniqueUuidId() {
        driveFullLifecycle();

        List<String> ids = env.events.events().stream().map(CloudEvent::id).toList();
        assertThat(ids).doesNotHaveDuplicates();
        for (String id : ids) {
            // UUID (v7) format — consumers dedupe on it
            assertThat(id).matches("^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
        }
    }

    @Test
    void thePlacedPayloadCarriesTheReservedMoney() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 100_000);
        env.placeHold.place("k1", wallet.id(), 30_000, "KES", Source.PAYMENTS,
                UUID.randomUUID(), "risk cleared");
        WalletEvents.HoldEventData holdData = null;
        for (CloudEvent event : env.events.events()) {
            if (event.type().equals(WalletEvents.HOLD_PLACED)) {
                holdData = (WalletEvents.HoldEventData) event.data();
            }
        }
        assertThat(holdData).isNotNull();
        assertThat(holdData.amount().amount_minor()).isEqualTo(30_000);
        assertThat(holdData.amount().currency()).isEqualTo("KES");
        assertThat(holdData.amount().exponent()).isEqualTo(2);
        assertThat(holdData.reason()).isEqualTo("risk cleared");
    }

    @Test
    void theCapturedPayloadSplitsTheReservedAmountExactly() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 100_000);
        var placed = env.placeHold.place("k1", wallet.id(), 30_000, "KES", Source.PAYMENTS,
                UUID.randomUUID(), null);
        env.events.reset();

        env.captureHold.capture("c1", placed.hold().id(), 12_000L, "partial");

        CloudEvent event = env.events.eventsOfType(WalletEvents.HOLD_CAPTURED).get(0);
        WalletEvents.HoldCapturedData data = (WalletEvents.HoldCapturedData) event.data();
        assertThat(data.amount().amount_minor()).isEqualTo(30_000);
        assertThat(data.captured_amount().amount_minor()).isEqualTo(12_000);
        assertThat(data.released_amount().amount_minor()).isEqualTo(18_000);
        assertThat(data.state()).isEqualTo("captured");
    }

    @Test
    void balanceChangedCarriesAllThreePartitions() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 100_000);
        env.events.reset();

        env.placeHold.place("k1", wallet.id(), 30_000, "KES", Source.PAYMENTS,
                UUID.randomUUID(), null);

        CloudEvent event = env.events.eventsOfType(WalletEvents.BALANCE_CHANGED).get(0);
        WalletEvents.WalletBalanceData data = (WalletEvents.WalletBalanceData) event.data();
        assertThat(data.balances().available()).isEqualTo(
                new com.sharkpay.wallet.api.dto.MoneyJson(70_000, "KES", 2));
        assertThat(data.balances().pending()).isEqualTo(
                new com.sharkpay.wallet.api.dto.MoneyJson(0, "KES", 2));
        assertThat(data.balances().held()).isEqualTo(
                new com.sharkpay.wallet.api.dto.MoneyJson(30_000, "KES", 2));
        assertThat(data.currency()).isEqualTo("KES");
        assertThat(data.principal_id()).isEqualTo(wallet.principalId());
    }

    @Test
    void theValidatorActuallyRejectsContractViolations() {
        driveFullLifecycle();
        CloudEvent event = env.events.events().stream()
                .filter(candidate -> candidate.type().equals(WalletEvents.HOLD_PLACED))
                .findFirst().orElseThrow();
        JsonSchemaCheck check = JsonSchemaCheck.load("wallet.holds.v1.json");

        // an extra property is rejected (additionalProperties: false)
        ObjectNode extra = (ObjectNode) mapper.valueToTree(event);
        extra.put("mystery_field", "x");
        assertThat(check.errors(extra)).anyMatch(error -> error.contains("mystery_field"));

        // a broken envelope constant is rejected
        ObjectNode badSpec = (ObjectNode) mapper.valueToTree(event);
        badSpec.put("specversion", "0.3");
        assertThat(check.errors(badSpec)).anyMatch(error -> error.contains("0.3"));

        // a wrong amount type is rejected
        ObjectNode badAmount = (ObjectNode) mapper.valueToTree(event);
        ((ObjectNode) badAmount.get("data").get("amount")).put("amount_minor", "50000");
        assertThat(check.errors(badAmount)).anyMatch(error -> error.contains("type"));
    }

    @Test
    void optionalFieldsAreOmittedNotNulled() {
        Wallet wallet = env.newWallet("KES");
        CloudEvent created = env.events.eventsOfType(WalletEvents.STATE_CHANGED).get(0);
        env.credit(wallet, 100_000);
        env.placeHold.place("k1", wallet.id(), 30_000, "KES", Source.PAYMENTS,
                UUID.randomUUID(), null);   // no reason
        env.events.reset();

        env.releaseHold.release("r1",
                env.holds.findActiveByWalletId(wallet.id()).get(0).id(), null);

        CloudEvent released = env.events.eventsOfType(WalletEvents.HOLD_RELEASED).get(0);
        ObjectNode tree = (ObjectNode) mapper.valueToTree(released);
        assertThat(tree.get("data").has("reason")).isFalse();
        // creation state events omit from_status
        ObjectNode stateTree = (ObjectNode) mapper.valueToTree(created);
        assertThat(stateTree.get("data").has("from_status")).isFalse();
    }

    // ------------------------------------------------------------------

    /** create → fund → hold → partial capture → release → freeze → unfreeze. */
    private void driveFullLifecycle() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 100_000);
        Wallet hold = null;
        var placed = env.placeHold.place("k1", wallet.id(), 30_000, "KES", Source.PAYMENTS,
                UUID.randomUUID(), "risk cleared");
        env.captureHold.capture("c1", placed.hold().id(), 12_000L, "partial");
        var second = env.placeHold.place("k2", wallet.id(), 5_000, "KES", Source.PAYOUTS,
                UUID.randomUUID(), null);
        env.releaseHold.release("r1", second.hold().id(), "not needed");
        env.changeStatus.freeze(wallet.id(), "case-9");
        env.changeStatus.unfreeze(wallet.id(), "case-9 cleared");
        assertThat(hold).isNull();   // placate unused-var analysis
    }

    private static String schemaFileOf(String type) {
        return switch (type) {
            case WalletEvents.HOLD_PLACED, WalletEvents.HOLD_RELEASED, WalletEvents.HOLD_CAPTURED
                    -> "wallet.holds.v1.json";
            case WalletEvents.STATE_CHANGED -> "wallet.state.v1.json";
            case WalletEvents.BALANCE_CHANGED -> "wallet.v1.json";
            default -> throw new IllegalArgumentException("unknown wallet event type: " + type);
        };
    }
}
