package com.sharkpay.reconciliation.storage;

import com.sharkpay.reconciliation.domain.ReconRun;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

/**
 * Spring Data repository behind {@link JpaReconRunRepository}.
 */
@Repository
public interface ReconRunJpaRepository extends JpaRepository<ReconRunEntity, String> {

    List<ReconRunEntity> findByProviderOrderByStartedAtDescIdDesc(String provider);
}
