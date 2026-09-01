package com.sharkpay.identity.storage;

import static org.assertj.core.api.Assertions.assertThat;

import com.sharkpay.identity.domain.KycRecord;
import com.sharkpay.identity.domain.KycStatus;
import com.sharkpay.identity.domain.KycTier;
import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.domain.PrincipalStatus;
import com.sharkpay.identity.domain.PrincipalType;
import com.sharkpay.identity.domain.SharkId;
import com.sharkpay.identity.domain.Device;
import com.sharkpay.identity.domain.DeviceStatus;
import com.sharkpay.identity.ports.IdempotentRequest;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class EntityMappingTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-01T10:00:30Z");
    private static final OffsetDateTime T1 = OffsetDateTime.parse("2026-09-02T11:01:31Z");

    @Test
    void principalRoundTripPreservesAllFields() {
        Principal agent = new Principal(UUID.randomUUID(), SharkId.fromData("ABC123"),
                PrincipalType.AGENT, UUID.randomUUID(), PrincipalStatus.SUSPENDED, KycTier.LIMITED, T0, T1);

        Principal domain = PrincipalEntity.fromDomain(agent).toDomain();

        assertThat(domain).isEqualTo(agent);
    }

    @Test
    void individualPrincipalRoundTrip() {
        Principal individual = new Principal(UUID.randomUUID(), SharkId.fromData("000000"),
                PrincipalType.INDIVIDUAL, null, PrincipalStatus.ACTIVE, KycTier.FULL, T0, T0);

        assertThat(PrincipalEntity.fromDomain(individual).toDomain()).isEqualTo(individual);
    }

    @Test
    void kycRecordRoundTripPending() {
        KycRecord pending = new KycRecord(UUID.randomUUID(), UUID.randomUUID(),
                KycTier.LIMITED, KycStatus.PENDING, "provider/ref", null, T0);

        assertThat(KycRecordEntity.fromDomain(pending).toDomain()).isEqualTo(pending);
    }

    @Test
    void kycRecordRoundTripDecided() {
        KycRecord rejected = new KycRecord(UUID.randomUUID(), UUID.randomUUID(),
                KycTier.FULL, KycStatus.REJECTED, null, T1, T0);

        assertThat(KycRecordEntity.fromDomain(rejected).toDomain()).isEqualTo(rejected);
    }

    @Test
    void deviceRoundTripActiveAndRevoked() {
        Device active = new Device(UUID.randomUUID(), UUID.randomUUID(),
                "ab".repeat(32), DeviceStatus.ACTIVE, T0);
        assertThat(DeviceEntity.fromDomain(active).toDomain()).isEqualTo(active);

        Device revoked = active.revoke();
        assertThat(DeviceEntity.fromDomain(revoked).toDomain()).isEqualTo(revoked);
    }

    @Test
    void idempotencyRoundTrip() {
        IdempotentRequest request = new IdempotentRequest("key-1", "fingerprint-1", UUID.randomUUID());

        IdempotencyEntity entity = IdempotencyEntity.fromDomain(request, T0);
        assertThat(entity.toDomain()).isEqualTo(request);
        assertThat(entity.toDomain().principalId()).isEqualTo(request.principalId());
    }
}
