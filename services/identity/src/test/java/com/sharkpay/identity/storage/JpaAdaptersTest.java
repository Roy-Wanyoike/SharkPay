package com.sharkpay.identity.storage;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.sharkpay.identity.domain.KycRecord;
import com.sharkpay.identity.domain.KycStatus;
import com.sharkpay.identity.domain.KycTier;
import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.domain.PrincipalStatus;
import com.sharkpay.identity.domain.PrincipalType;
import com.sharkpay.identity.domain.SharkId;
import com.sharkpay.identity.domain.Device;
import com.sharkpay.identity.domain.DeviceStatus;
import com.sharkpay.identity.ports.Clock;
import com.sharkpay.identity.ports.IdempotentRequest;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/**
 * Covers the JPA port adapters by mocking the Spring Data interfaces: no
 * database, no Spring context — pure delegation + mapping verification.
 */
class JpaAdaptersTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-01T10:00:30Z");

    @Test
    void principalAdapterDelegatesAndMaps() {
        PrincipalJpaRepository jpa = Mockito.mock(PrincipalJpaRepository.class);
        JpaPrincipalRepository adapter = new JpaPrincipalRepository(jpa);
        Principal principal = new Principal(UUID.randomUUID(), SharkId.fromData("ABC123"),
                PrincipalType.INDIVIDUAL, null, PrincipalStatus.ACTIVE, KycTier.UNVERIFIED, T0, T0);

        when(jpa.save(any(PrincipalEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));
        when(jpa.findById(principal.id()))
                .thenReturn(Optional.of(PrincipalEntity.fromDomain(principal)));
        when(jpa.findBySharkId(principal.sharkId().value()))
                .thenReturn(Optional.of(PrincipalEntity.fromDomain(principal)));

        assertThat(adapter.save(principal)).isEqualTo(principal);
        assertThat(adapter.findById(principal.id())).contains(principal);
        assertThat(adapter.findBySharkId(principal.sharkId())).contains(principal);

        verify(jpa).save(any(PrincipalEntity.class));
        verify(jpa).findById(principal.id());
        verify(jpa).findBySharkId(principal.sharkId().value());

        when(jpa.findById(UUID.randomUUID())).thenReturn(Optional.empty());
        when(jpa.findBySharkId("SP-0000-0001")).thenReturn(Optional.empty());
        assertThat(adapter.findById(UUID.randomUUID())).isEmpty();
        assertThat(adapter.findBySharkId(SharkId.of("SP-0000-0001"))).isEmpty();
    }

    @Test
    void kycAdapterDelegatesAndMaps() {
        KycRecordJpaRepository jpa = Mockito.mock(KycRecordJpaRepository.class);
        JpaKycRepository adapter = new JpaKycRepository(jpa);
        UUID principalId = UUID.randomUUID();
        KycRecord record = new KycRecord(UUID.randomUUID(), principalId, KycTier.LIMITED,
                KycStatus.APPROVED, "ref", T0, T0);

        when(jpa.save(any(KycRecordEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jpa.findByPrincipalId(principalId))
                .thenReturn(List.of(KycRecordEntity.fromDomain(record)));

        assertThat(adapter.save(record)).isEqualTo(record);
        assertThat(adapter.findByPrincipalId(principalId)).containsExactly(record);
        verify(jpa).save(any(KycRecordEntity.class));
        verify(jpa).findByPrincipalId(principalId);
    }

    @Test
    void deviceAdapterDelegatesAndMaps() {
        DeviceJpaRepository jpa = Mockito.mock(DeviceJpaRepository.class);
        JpaDeviceRepository adapter = new JpaDeviceRepository(jpa);
        UUID principalId = UUID.randomUUID();
        Device device = new Device(UUID.randomUUID(), principalId,
                "ab".repeat(32), DeviceStatus.ACTIVE, T0);

        when(jpa.save(any(DeviceEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jpa.findById(device.id())).thenReturn(Optional.of(DeviceEntity.fromDomain(device)));
        when(jpa.findByPrincipalIdOrderByCreatedAtAscIdAsc(principalId)).thenReturn(List.of(DeviceEntity.fromDomain(device)));
        when(jpa.findByPrincipalIdAndFingerprintHash(principalId, device.fingerprintHash()))
                .thenReturn(Optional.of(DeviceEntity.fromDomain(device)));

        assertThat(adapter.save(device)).isEqualTo(device);
        assertThat(adapter.findById(device.id())).contains(device);
        assertThat(adapter.findByPrincipalId(principalId)).containsExactly(device);
        assertThat(adapter.findByPrincipalIdAndFingerprintHash(principalId, device.fingerprintHash()))
                .contains(device);

        assertThat(adapter.findById(UUID.randomUUID())).isEmpty();
        assertThat(adapter.findByPrincipalId(UUID.randomUUID())).isEmpty();
        assertThat(adapter.findByPrincipalIdAndFingerprintHash(principalId, "cd".repeat(32))).isEmpty();
    }

    @Test
    void idempotencyAdapterDelegatesAndMaps() {
        IdempotencyJpaRepository jpa = Mockito.mock(IdempotencyJpaRepository.class);
        Clock clock = () -> T0;
        JpaIdempotencyStore adapter = new JpaIdempotencyStore(jpa, clock);
        IdempotentRequest request = new IdempotentRequest("key-1", "fingerprint", UUID.randomUUID());

        when(jpa.findById("key-1")).thenReturn(Optional.of(IdempotencyEntity.fromDomain(request, T0)));
        when(jpa.save(any(IdempotencyEntity.class))).thenAnswer(inv -> inv.getArgument(0));

        assertThat(adapter.findByKey("key-1")).contains(request);
        adapter.save(request);

        verify(jpa).findById("key-1");
        verify(jpa).save(any(IdempotencyEntity.class));
        assertThat(adapter.findByKey("other")).isEmpty();
    }
}
