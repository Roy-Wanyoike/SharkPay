package com.sharkpay.identity.ports;

import com.sharkpay.identity.domain.KycRecord;
import java.util.List;
import java.util.UUID;

/**
 * Persistence port for KYC decision records (append-only audit log).
 */
public interface KycRepository {

    KycRecord save(KycRecord record);

    List<KycRecord> findByPrincipalId(UUID principalId);
}
