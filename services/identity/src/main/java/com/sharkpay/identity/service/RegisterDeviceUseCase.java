package com.sharkpay.identity.service;

import com.sharkpay.identity.domain.Device;
import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.domain.PrincipalStatus;
import com.sharkpay.identity.domain.exception.ConflictException;
import com.sharkpay.identity.domain.exception.NotFoundException;
import com.sharkpay.identity.ports.Clock;
import com.sharkpay.identity.ports.DeviceRepository;
import com.sharkpay.identity.ports.PrincipalRepository;
import java.util.Locale;
import java.util.UUID;

/**
 * Registers a device fingerprint (sha-256 hex) for a principal. Duplicate
 * fingerprints for the same principal are rejected; CLOSED principals
 * cannot register devices.
 */
public final class RegisterDeviceUseCase {

    private final PrincipalRepository principalRepository;
    private final DeviceRepository deviceRepository;
    private final Clock clock;

    public RegisterDeviceUseCase(PrincipalRepository principalRepository,
                                 DeviceRepository deviceRepository,
                                 Clock clock) {
        this.principalRepository = principalRepository;
        this.deviceRepository = deviceRepository;
        this.clock = clock;
    }

    public Device execute(UUID principalId, String fingerprint) {
        Principal principal = principalRepository.findById(principalId)
                .orElseThrow(() -> new NotFoundException("PRINCIPAL_NOT_FOUND",
                        "no principal with id " + principalId));
        if (principal.status() == PrincipalStatus.CLOSED) {
            throw new ConflictException("PRINCIPAL_CLOSED",
                    "devices cannot be registered for a CLOSED principal");
        }
        String normalized = fingerprint == null ? null : fingerprint.trim().toLowerCase(Locale.ROOT);
        if (normalized == null || normalized.isEmpty()) {
            throw new com.sharkpay.identity.domain.exception.ValidationException("INVALID_FINGERPRINT",
                    "device fingerprint must not be blank");
        }
        deviceRepository.findByPrincipalIdAndFingerprintHash(principalId, normalized)
                .ifPresent(existing -> {
                    throw new ConflictException("DEVICE_ALREADY_REGISTERED",
                            "device " + existing.id() + " already registered fingerprint "
                                    + existing.fingerprintHash() + " for principal " + principalId);
                });
        Device device = Device.register(UUID.randomUUID(), principalId, normalized, clock.now());
        return deviceRepository.save(device);
    }
}
