package com.sharkpay.identity.storage;

import com.sharkpay.identity.domain.Principal;
import com.sharkpay.identity.domain.SharkId;
import com.sharkpay.identity.ports.PrincipalRepository;
import java.util.Optional;
import java.util.UUID;

/**
 * Adapter implementing the {@link PrincipalRepository} port on top of
 * {@link PrincipalJpaRepository}. The domain-typed port methods cannot live
 * directly on the Spring Data interface because JpaRepository's
 * save/findById signatures are entity-typed and would clash, hence this
 * thin mapping adapter.
 */
@org.springframework.stereotype.Repository
public class JpaPrincipalRepository implements PrincipalRepository {

    private final PrincipalJpaRepository entities;

    public JpaPrincipalRepository(PrincipalJpaRepository entities) {
        this.entities = entities;
    }

    @Override
    public Principal save(Principal principal) {
        return entities.save(PrincipalEntity.fromDomain(principal)).toDomain();
    }

    @Override
    public Optional<Principal> findById(UUID id) {
        return entities.findById(id).map(PrincipalEntity::toDomain);
    }

    @Override
    public Optional<Principal> findBySharkId(SharkId sharkId) {
        return entities.findBySharkId(sharkId.value()).map(PrincipalEntity::toDomain);
    }
}
