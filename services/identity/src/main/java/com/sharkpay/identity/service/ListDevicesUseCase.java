package com.sharkpay.identity.service;

import com.sharkpay.identity.domain.Device;
import com.sharkpay.identity.domain.exception.NotFoundException;
import com.sharkpay.identity.ports.DeviceRepository;
import com.sharkpay.identity.ports.PrincipalRepository;
import java.util.List;
import java.util.UUID;

/**
 * Lists the devices bound to a principal, ordered by registration time.
 * Ordering is a repository concern (the JPA adapter orders by
 * created_at, id; the in-memory fake preserves registration order), so the
 * use case never re-sorts — re-sorting here with a tied clock timestamp
 * would fall through to a random id tie-break.
 */
public final class ListDevicesUseCase {

    private final PrincipalRepository principalRepository;
    private final DeviceRepository deviceRepository;

    public ListDevicesUseCase(PrincipalRepository principalRepository, DeviceRepository deviceRepository) {
        this.principalRepository = principalRepository;
        this.deviceRepository = deviceRepository;
    }

    public List<Device> execute(UUID principalId) {
        principalRepository.findById(principalId)
                .orElseThrow(() -> new NotFoundException("PRINCIPAL_NOT_FOUND",
                        "no principal with id " + principalId));
        return List.copyOf(deviceRepository.findByPrincipalId(principalId));
    }
}
