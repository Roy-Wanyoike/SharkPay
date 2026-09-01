package com.sharkpay.identity.storage;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link DeviceEntity}.
 */
@Repository
public interface DeviceJpaRepository extends JpaRepository<DeviceEntity, UUID> {

    List<DeviceEntity> findByPrincipalIdOrderByCreatedAtAscIdAsc(UUID principalId);

    Optional<DeviceEntity> findByPrincipalIdAndFingerprintHash(UUID principalId, String fingerprintHash);
}
