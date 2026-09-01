package com.sharkpay.identity.service;

import com.sharkpay.identity.domain.Device;
import com.sharkpay.identity.domain.exception.NotFoundException;
import com.sharkpay.identity.ports.DeviceRepository;
import com.sharkpay.identity.ports.PrincipalRepository;
import java.util.UUID;

/**
 * Revokes (one-way) a device binding. The device must belong to the
 * principal in the path.
 */
public final class RevokeDeviceUseCase {

    private final PrincipalRepository principalRepository;
    private final DeviceRepository deviceRepository;

    public RevokeDeviceUseCase(PrincipalRepository principalRepository, DeviceRepository deviceRepository) {
        this.principalRepository = principalRepository;
        this.deviceRepository = deviceRepository;
    }

    public Device execute(UUID principalId, UUID deviceId) {
        principalRepository.findById(principalId)
                .orElseThrow(() -> new NotFoundException("PRINCIPAL_NOT_FOUND",
                        "no principal with id " + principalId));
        Device device = deviceRepository.findById(deviceId)
                .filter(d -> d.principalId().equals(principalId))
                .orElseThrow(() -> new NotFoundException("DEVICE_NOT_FOUND",
                        "no device " + deviceId + " for principal " + principalId));
        Device revoked = device.revoke();
        return deviceRepository.save(revoked);
    }
}
