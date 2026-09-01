package com.sharkpay.identity.fakes;

import com.sharkpay.identity.domain.KycRecord;
import com.sharkpay.identity.ports.KycRepository;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * In-memory {@link KycRepository} fake (append-only).
 */
public final class InMemoryKycRepository implements KycRepository {

    private final List<KycRecord> records = new CopyOnWriteArrayList<>();

    @Override
    public KycRecord save(KycRecord record) {
        records.add(record);
        return record;
    }

    @Override
    public List<KycRecord> findByPrincipalId(UUID principalId) {
        return records.stream()
                .filter(record -> record.principalId().equals(principalId))
                .toList();
    }

    public List<KycRecord> all() {
        return List.copyOf(records);
    }
}
