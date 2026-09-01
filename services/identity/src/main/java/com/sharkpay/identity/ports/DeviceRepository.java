package com.sharkpay.identity.ports;

import com.sharkpay.identity.domain.Device;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Persistence port for device bindings.
 */
public interface DeviceRepository {

    Device save(Device device);

    Optional<Device> findById(UUID id);

    List<Device> findByPrincipalId(UUID principalId);

    Optional<Device> findByPrincipalIdAndFingerprintHash(UUID principalId, String fingerprintHash);
}
