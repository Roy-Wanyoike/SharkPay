package com.sharkpay.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sharkpay.identity.domain.KycTier;
import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.domain.PrincipalStatus;
import com.sharkpay.identity.domain.PrincipalType;
import com.sharkpay.identity.domain.SharkId;
import com.sharkpay.identity.domain.exception.ValidationException;
import com.sharkpay.identity.fakes.IdentityHarness;
import com.sharkpay.identity.ports.event.CloudEvent;
import com.sharkpay.identity.ports.event.EventIds;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class IdentityEventsTest {

    private final IdentityHarness harness = new IdentityHarness();

    @Test
    void principalCreatedPayload() {
        OffsetDateTime at = OffsetDateTime.parse("2026-09-01T10:00:30Z");
        Principal agent = new Principal(UUID.randomUUID(), SharkId.fromData("ABC123"),
                PrincipalType.AGENT, UUID.randomUUID(), PrincipalStatus.ACTIVE, KycTier.UNVERIFIED, at, at);

        CloudEvent event = IdentityEvents.principalCreated(agent, at);

        assertThat(event.type()).isEqualTo("identity.principal.created.v1");
        assertThat(event.specversion()).isEqualTo("1.0");
        assertThat(event.source()).isEqualTo("sharkpay/identity");
        assertThat(event.time()).isEqualTo(at);
        assertThat(event.id()).isNotBlank();
        assertThat(event.data())
                .containsEntry("principal_id", agent.id().toString())
                .containsEntry("shark_id", "SP-ABC1-23" + SharkId.checksumFor("ABC123"))
                .containsEntry("type", "AGENT")
                .containsEntry("status", "ACTIVE")
                .containsEntry("owner_principal_id", agent.ownerPrincipalId().toString())
                .containsEntry("kyc_tier", "UNVERIFIED");
    }

    @Test
    void principalCreatedPayloadOmitsOwnerForNonAgents() {
        OffsetDateTime at = OffsetDateTime.parse("2026-09-01T10:00:30Z");
        Principal individual = new Principal(UUID.randomUUID(), SharkId.fromData("ABC123"),
                PrincipalType.INDIVIDUAL, null, PrincipalStatus.ACTIVE, KycTier.LIMITED, at, at);

        CloudEvent event = IdentityEvents.principalCreated(individual, at);

        assertThat(event.data()).doesNotContainKey("owner_principal_id");
        assertThat(event.data()).containsEntry("kyc_tier", "LIMITED");
    }

    @Test
    void statusChangedPayload() {
        OffsetDateTime at = OffsetDateTime.parse("2026-09-01T11:00:00Z");
        Principal before = harness.individual();
        Principal after = before.withStatus(PrincipalStatus.SUSPENDED, at);

        CloudEvent event = IdentityEvents.principalStatusChanged(before, after, at);

        assertThat(event.type()).isEqualTo("identity.principal.status.changed.v1");
        assertThat(event.data())
                .containsEntry("principal_id", after.id().toString())
                .containsEntry("shark_id", after.sharkId().value())
                .containsEntry("from_status", "ACTIVE")
                .containsEntry("to_status", "SUSPENDED");
    }

    @Test
    void tierChangedPayloadWithAndWithoutProviderRef() {
        OffsetDateTime at = OffsetDateTime.parse("2026-09-01T11:00:00Z");
        Principal principal = harness.individual();

        CloudEvent withRef = IdentityEvents.kycTierChanged(principal, KycTier.UNVERIFIED,
                KycTier.LIMITED, IdentityEvents.REASON_KYC_DECISION, "prov-9", at);
        assertThat(withRef.type()).isEqualTo("identity.kyc.tier.changed.v1");
        assertThat(withRef.data())
                .containsEntry("from_tier", "UNVERIFIED")
                .containsEntry("to_tier", "LIMITED")
                .containsEntry("reason", "kyc_decision")
                .containsEntry("provider_ref", "prov-9");

        CloudEvent withoutRef = IdentityEvents.kycTierChanged(principal, KycTier.LIMITED,
                KycTier.UNVERIFIED, IdentityEvents.REASON_SUSPENSION_RESET, null, at);
        assertThat(withoutRef.data())
                .containsEntry("reason", "suspension_reset")
                .doesNotContainKey("provider_ref");
    }

    @Test
    void everyEventGetsAFreshId() {
        OffsetDateTime at = OffsetDateTime.parse("2026-09-01T11:00:00Z");
        Principal principal = harness.individual();

        CloudEvent first = IdentityEvents.principalCreated(principal, at);
        CloudEvent second = IdentityEvents.principalCreated(principal, at);
        assertThat(first.id()).isNotEqualTo(second.id());
        assertThat(first.data()).isEqualTo(second.data());
    }

    @Test
    void dataMapIsDefensivelyCopied() {
        OffsetDateTime at = OffsetDateTime.parse("2026-09-01T11:00:00Z");
        CloudEvent event = CloudEvent.of("x.y.z.v1", "sharkpay/identity",
                EventIds.uuidV7().toString(), at, null);
        assertThat(event.data()).isEmpty();
        assertThatThrownBy(() -> event.data().put("k", "v"))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void cloudEventRequiresSpecversionOne() {
        OffsetDateTime at = OffsetDateTime.parse("2026-09-01T11:00:00Z");
        assertThatThrownBy(() -> new CloudEvent("0.3", "t", "s", "id", at, null))
                .isInstanceOf(ValidationException.class);
        assertThatThrownBy(() -> new CloudEvent(null, "t", "s", "id", at, null))
                .isInstanceOf(ValidationException.class);
        assertThatCode(() -> new CloudEvent("1.0", "t", "s", "id", at, null))
                .doesNotThrowAnyException();
        assertThatThrownBy(() -> new CloudEvent("1.0", null, "s", "id", at, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CloudEvent("1.0", "t", null, "id", at, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CloudEvent("1.0", "t", "s", null, at, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new CloudEvent("1.0", "t", "s", "id", null, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Nested
    class UuidV7 {

        @Test
        void generatesVersion7IetfVariantUuids() {
            UUID id = EventIds.uuidV7();
            assertThat(id.version()).isEqualTo(7);
            assertThat(id.variant()).isEqualTo(2);
            assertThat(id.toString()).matches(
                    "^[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$");
        }

        @Test
        void embedsTheCurrentUnixMillis() {
            long before = System.currentTimeMillis();
            UUID id = EventIds.uuidV7();
            long after = System.currentTimeMillis();
            long embedded = id.getMostSignificantBits() >>> 16;
            assertThat(embedded).isBetween(before, after);
        }

        @Test
        void idsAreUniqueAndOrderedWithinGeneration() {
            Set<UUID> seen = new HashSet<>();
            UUID previous = null;
            for (int i = 0; i < 1_000; i++) {
                UUID id = EventIds.uuidV7();
                assertThat(seen.add(id)).isTrue();
                if (previous != null) {
                    assertThat(id.getMostSignificantBits() >>> 16)
                            .isGreaterThanOrEqualTo(previous.getMostSignificantBits() >>> 16);
                }
                previous = id;
            }
        }
    }
}
