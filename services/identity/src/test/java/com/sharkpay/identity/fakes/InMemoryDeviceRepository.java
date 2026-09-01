package com.sharkpay.identity.fakes;

import com.sharkpay.identity.domain.Device;
import com.sharkpay.identity.ports.DeviceRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.LinkedHashMap;
import java.util.stream.Collectors;

/**
 * In-memory {@link DeviceRepository} fake. Insertion order is preserved so
 * that devices registered under an identical (fixed) clock timestamp list in
 * registration order — the tie-break a real database gets from its b-tree
 * on (created_at, id) is approximated deterministically here.
 */
public final class InMemoryDeviceRepository implements DeviceRepository {

    private final Map<UUID, Device> byId = new LinkedHashMap<>();

    @Override
    public synchronized Device save(Device device) {
        byId.put(device.id(), device);
        return device;
    }

    @Override
    public synchronized Optional<Device> findById(UUID id) {
        return Optional.ofNullable(byId.get(id));
    }

    @Override
    public synchronized List<Device> findByPrincipalId(UUID principalId) {
        return byId.values().stream()
                .filter(device -> device.principalId().equals(principalId))
                .sorted(Comparator.comparing(Device::createdAt))
                .collect(Collectors.toList());
    }

    @Override
    public synchronized Optional<Device> findByPrincipalIdAndFingerprintHash(UUID principalId, String fingerprintHash) {
        return byId.values().stream()
                .filter(device -> device.principalId().equals(principalId)
                        && device.fingerprintHash().equals(fingerprintHash))
                .findFirst();
    }

    public synchronized int count() {
        return byId.size();
    }
}
