package com.sharkpay.identity.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sharkpay.identity.domain.exception.ConflictException;
import com.sharkpay.identity.domain.exception.ValidationException;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DeviceTest {

    private static final OffsetDateTime T0 = OffsetDateTime.parse("2026-09-01T10:00:30Z");
    private static final String PRINT = "a".repeat(64);

    @Test
    void registerNormalizesHexCase() {
        Device device = Device.register(UUID.randomUUID(), UUID.randomUUID(),
                "ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789ABCDEF0123456789", T0);
        assertThat(device.fingerprintHash())
                .isEqualTo("abcdef0123456789abcdef0123456789abcdef0123456789abcdef0123456789");
        assertThat(device.status()).isEqualTo(DeviceStatus.ACTIVE);
        assertThat(device.createdAt()).isEqualTo(T0);
    }

    @Test
    void registerRejectsInvalidFingerprints() {
        UUID principalId = UUID.randomUUID();
        String[] invalid = {
                null, "", "   ",
                "a".repeat(63),          // too short
                "a".repeat(65),          // too long
                "z".repeat(64),          // not hex
                "A".repeat(63) + "g"     // non-hex tail
        };
        for (String fingerprint : invalid) {
            assertThatThrownBy(() -> Device.register(UUID.randomUUID(), principalId, fingerprint, T0))
                    .as("fingerprint '%s' must be rejected", fingerprint)
                    .isInstanceOf(ValidationException.class)
                    .extracting(e -> ((ValidationException) e).code())
                    .isEqualTo("INVALID_FINGERPRINT");
        }
    }

    @Test
    void constructorEnforcesRequiredFields() {
        UUID id = UUID.randomUUID();
        UUID principalId = UUID.randomUUID();
        assertThatThrownBy(() -> new Device(null, principalId, PRINT, DeviceStatus.ACTIVE, T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Device(id, null, PRINT, DeviceStatus.ACTIVE, T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Device(id, principalId, null, DeviceStatus.ACTIVE, T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Device(id, principalId, PRINT, null, T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new Device(id, principalId, PRINT, DeviceStatus.ACTIVE, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void revokeIsOneWay() {
        Device device = Device.register(UUID.randomUUID(), UUID.randomUUID(), PRINT, T0);
        Device revoked = device.revoke();
        assertThat(revoked.status()).isEqualTo(DeviceStatus.REVOKED);
        assertThat(revoked.id()).isEqualTo(device.id());
        assertThat(revoked.fingerprintHash()).isEqualTo(device.fingerprintHash());
        assertThatThrownBy(revoked::revoke)
                .isInstanceOf(ConflictException.class)
                .extracting(e -> ((ConflictException) e).code())
                .isEqualTo("DEVICE_ALREADY_REVOKED");
    }
}
