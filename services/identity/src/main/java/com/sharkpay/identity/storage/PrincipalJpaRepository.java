package com.sharkpay.identity.storage;

import java.util.Optional;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

/**
 * Spring Data repository for {@link PrincipalEntity}. Derived queries only;
 * domain-typed port access goes through {@link JpaPrincipalRepository}.
 */
@Repository
public interface PrincipalJpaRepository extends JpaRepository<PrincipalEntity, UUID> {

    Optional<PrincipalEntity> findBySharkId(String sharkId);
}
