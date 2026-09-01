package com.sharkpay.identity.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sharkpay.identity.domain.Device;
import com.sharkpay.identity.domain.DeviceStatus;
import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.domain.exception.ConflictException;
import com.sharkpay.identity.domain.exception.NotFoundException;
import com.sharkpay.identity.domain.exception.ValidationException;
import com.sharkpay.identity.fakes.IdentityHarness;
import java.util.UUID;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

class DeviceUseCasesTest {

    private static final String PRINT = "ab".repeat(32);
    private static final String OTHER_PRINT = "cd".repeat(32);

    private final IdentityHarness harness = new IdentityHarness();

    @Nested
    class Register {

        @Test
        void registersAFingerprintForAnActivePrincipal() {
            Principal principal = harness.individual();

            Device device = harness.registerDevice.execute(principal.id(), PRINT.toUpperCase());

            assertThat(device.id()).isNotNull();
            assertThat(device.principalId()).isEqualTo(principal.id());
            assertThat(device.fingerprintHash()).isEqualTo(PRINT); // normalized
            assertThat(device.status()).isEqualTo(DeviceStatus.ACTIVE);
            assertThat(device.createdAt()).isEqualTo(harness.clock.now());
        }

        @Test
        void sameFingerprintPerPrincipalIsRejectedButAllowedForOtherPrincipals() {
            Principal first = harness.individual();
            Principal second = harness.individual();

            harness.registerDevice.execute(first.id(), PRINT);
            assertThatThrownBy(() -> harness.registerDevice.execute(first.id(), PRINT))
                    .isInstanceOf(ConflictException.class)
                    .extracting(e -> ((ConflictException) e).code())
                    .isEqualTo("DEVICE_ALREADY_REGISTERED");

            Device secondPrincipalDevice = harness.registerDevice.execute(second.id(), PRINT);
            assertThat(secondPrincipalDevice.principalId()).isEqualTo(second.id());
        }

        @Test
        void invalidFingerprintsAreRejected() {
            Principal principal = harness.individual();
            assertThatThrownBy(() -> harness.registerDevice.execute(principal.id(), "not-a-hash"))
                    .isInstanceOf(ValidationException.class)
                    .extracting(e -> ((ValidationException) e).code())
                    .isEqualTo("INVALID_FINGERPRINT");
            assertThatThrownBy(() -> harness.registerDevice.execute(principal.id(), null))
                    .isInstanceOf(ValidationException.class)
                    .extracting(e -> ((ValidationException) e).code())
                    .isEqualTo("INVALID_FINGERPRINT");
        }

        @Test
        void closedPrincipalsCannotRegisterDevices() {
            Principal closed = harness.closedIndividual();
            assertThatThrownBy(() -> harness.registerDevice.execute(closed.id(), PRINT))
                    .isInstanceOf(ConflictException.class)
                    .extracting(e -> ((ConflictException) e).code())
                    .isEqualTo("PRINCIPAL_CLOSED");
        }

        @Test
        void unknownPrincipalIs404() {
            assertThatThrownBy(() -> harness.registerDevice.execute(UUID.randomUUID(), PRINT))
                    .isInstanceOf(NotFoundException.class)
                    .extracting(e -> ((NotFoundException) e).code())
                    .isEqualTo("PRINCIPAL_NOT_FOUND");
        }
    }

    @Nested
    class ListAndRevoke {

        @Test
        void listsDevicesOrderedByRegistrationTime() {
            Principal principal = harness.individual();
            Device first = harness.registerDevice.execute(principal.id(), PRINT);
            harness.clock.advanceBy(java.time.Duration.ofMinutes(1));
            Device second = harness.registerDevice.execute(principal.id(), OTHER_PRINT);

            assertThat(harness.listDevices.execute(principal.id()))
                    .containsExactly(first, second);
        }

        @Test
        void listOfUnknownPrincipalIs404() {
            assertThatThrownBy(() -> harness.listDevices.execute(UUID.randomUUID()))
                    .isInstanceOf(NotFoundException.class)
                    .extracting(e -> ((NotFoundException) e).code())
                    .isEqualTo("PRINCIPAL_NOT_FOUND");
        }

        @Test
        void revokesOnlyTheTargetDevice() {
            Principal principal = harness.individual();
            Device first = harness.registerDevice.execute(principal.id(), PRINT);
            Device second = harness.registerDevice.execute(principal.id(), OTHER_PRINT);

            Device revoked = harness.revokeDevice.execute(principal.id(), first.id());

            assertThat(revoked.status()).isEqualTo(DeviceStatus.REVOKED);
            assertThat(harness.devices.findById(second.id()))
                    .hasValueSatisfying(d -> assertThat(d.status()).isEqualTo(DeviceStatus.ACTIVE));
            assertThat(harness.listDevices.execute(principal.id()))
                    .extracting(Device::status)
                    .containsExactly(DeviceStatus.REVOKED, DeviceStatus.ACTIVE);
        }

        @Test
        void revokeOfUnknownOrForeignDeviceIs404() {
            Principal principal = harness.individual();
            Principal other = harness.individual();
            Device foreignDevice = harness.registerDevice.execute(other.id(), PRINT);

            assertThatThrownBy(() -> harness.revokeDevice.execute(principal.id(), UUID.randomUUID()))
                    .isInstanceOf(NotFoundException.class)
                    .extracting(e -> ((NotFoundException) e).code())
                    .isEqualTo("DEVICE_NOT_FOUND");
            assertThatThrownBy(() -> harness.revokeDevice.execute(principal.id(), foreignDevice.id()))
                    .isInstanceOf(NotFoundException.class)
                    .extracting(e -> ((NotFoundException) e).code())
                    .isEqualTo("DEVICE_NOT_FOUND");
            assertThatThrownBy(() -> harness.revokeDevice.execute(UUID.randomUUID(), foreignDevice.id()))
                    .isInstanceOf(NotFoundException.class)
                    .extracting(e -> ((NotFoundException) e).code())
                    .isEqualTo("PRINCIPAL_NOT_FOUND");
        }

        @Test
        void doubleRevokeIsRejected() {
            Principal principal = harness.individual();
            Device device = harness.registerDevice.execute(principal.id(), PRINT);
            harness.revokeDevice.execute(principal.id(), device.id());

            assertThatThrownBy(() -> harness.revokeDevice.execute(principal.id(), device.id()))
                    .isInstanceOf(ConflictException.class)
                    .extracting(e -> ((ConflictException) e).code())
                    .isEqualTo("DEVICE_ALREADY_REVOKED");
        }
    }
}
