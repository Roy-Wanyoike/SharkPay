package com.sharkpay.identity.storage;

import com.sharkpay.identity.domain.Device;
import com.sharkpay.identity.ports.DeviceRepository;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter implementing the {@link DeviceRepository} port on top of
 * {@link DeviceJpaRepository}.
 */
@org.springframework.stereotype.Repository
public class JpaDeviceRepository implements DeviceRepository {

    private final DeviceJpaRepository entities;

    public JpaDeviceRepository(DeviceJpaRepository entities) {
        this.entities = entities;
    }

    @Override
    public Device save(Device device) {
        return entities.save(DeviceEntity.fromDomain(device)).toDomain();
    }

    @Override
    public Optional<Device> findById(UUID id) {
        return entities.findById(id).map(DeviceEntity::toDomain);
    }

    @Override
    public List<Device> findByPrincipalId(UUID principalId) {
        return entities.findByPrincipalIdOrderByCreatedAtAscIdAsc(principalId).stream()
                .map(DeviceEntity::toDomain)
                .toList();
    }

    @Override
    public Optional<Device> findByPrincipalIdAndFingerprintHash(UUID principalId, String fingerprintHash) {
        return entities.findByPrincipalIdAndFingerprintHash(principalId, fingerprintHash)
                .map(DeviceEntity::toDomain);
    }
}
