package com.sharkpay.gateway.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Spring Data repository for {@link ApiKeyEntity} (id-ordered access paths).
 */
public interface ApiKeyJpaRepository extends JpaRepository<ApiKeyEntity, String> {

    Optional<ApiKeyEntity> findBySecretHash(String secretHash);

    List<ApiKeyEntity> findByPrincipalIdOrderByIdAsc(UUID principalId);

    /** Keyed lookup used by the in-adapter cursor filter. */
    @Query("select k from ApiKeyEntity k where k.id > :cursor order by k.id asc")
    List<ApiKeyEntity> findAllAfterCursor(@Param("cursor") String cursor);
}
