package com.sharkpay.reconciliation.storage;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;

/**
 * Spring Data repository behind {@link JpaReconBreakRepository} — queries
 * against the Flyway-managed {@code recon_breaks} table.
 */
@Repository
public interface ReconBreakJpaRepository extends JpaRepository<ReconBreakEntity, String> {

    List<ReconBreakEntity> findByRunIdOrderByDetectedAtAscIdAsc(String runId);

    List<ReconBreakEntity> findByStateOrderByDetectedAtAscIdAsc(String state);

    @Query("select b from ReconBreakEntity b where b.state in :states "
            + "order by b.detectedAt asc, b.id asc")
    List<ReconBreakEntity> findByStatesOrderByDetectedAtAscIdAsc(@Param("states") List<String> states);

    List<ReconBreakEntity> findByProviderOrderByDetectedAtAscIdAsc(String provider);
}
