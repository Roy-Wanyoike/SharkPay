package com.sharkpay.risk.storage;

import com.sharkpay.money.Money;
import com.sharkpay.risk.ports.VelocityCounterStore;
import org.springframework.stereotype.Repository;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * JPA adapter for {@link VelocityCounterStore}: minute buckets in
 * velocity_counters (see VelocityBuckets for the approximation contract).
 * Untested locally (ADR 003); VelocityBuckets is unit tested.
 */
@Repository
public class VelocityCounterStoreAdapter implements VelocityCounterStore {

    private final VelocityCounterJpaRepository repo;
    private final Clock clock;

    public VelocityCounterStoreAdapter(VelocityCounterJpaRepository repo, Clock clock) {
        this.repo = repo;
        this.clock = clock;
    }

    @Override
    public int countInWindow(String subject, Duration window) {
        return Math.toIntExact(repo.sumTxnCount(subject, buckets(window)));
    }

    @Override
    public Money amountInWindow(String subject, String currency, Duration window) {
        long sum = repo.sumAmountMinor(subject, currency, buckets(window));
        return sum == 0 ? Money.zero(currency) : Money.of(sum, currency);
    }

    @Override
    public void record(String subject, Money amount, Instant occurredAt) {
        String bucket = VelocityBuckets.bucketId(occurredAt);
        VelocityCounterId id = new VelocityCounterId(subject, bucket, amount.currency());
        Optional<VelocityCounterEntity> existing = repo.findById(id);
        if (existing.isPresent()) {
            VelocityCounterEntity row = existing.get();
            row.txnCount++;
            row.amountMinor += amount.amountMinor();
            repo.save(row);
        } else {
            repo.save(new VelocityCounterEntity(subject, bucket, amount.currency(), 1, amount.amountMinor()));
        }
    }

    private List<String> buckets(Duration window) {
        return VelocityBuckets.windowBucketIds(window, clock.instant());
    }
}
