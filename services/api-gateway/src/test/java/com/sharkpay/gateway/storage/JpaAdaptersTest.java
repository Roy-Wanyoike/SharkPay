package com.sharkpay.gateway.storage;

import com.sharkpay.gateway.domain.ApiKey;
import com.sharkpay.gateway.domain.ApiKeyStatus;
import com.sharkpay.gateway.domain.QuotaDecision;
import com.sharkpay.gateway.domain.QuotaWindows;
import com.sharkpay.gateway.domain.Scope;
import com.sharkpay.gateway.domain.SubscriptionState;
import com.sharkpay.gateway.domain.WebhookDelivery;
import com.sharkpay.gateway.domain.WebhookSubscription;
import com.sharkpay.gateway.ports.IdempotencyCache;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * JPA adapter behavior with Mockito-doubled Spring Data repositories (the
 * wallet exemplar pattern — no database, ADR 003): entity mapping
 * round-trips, update-vs-insert paths, cursor pagination, due-work
 * selection and quota bucket arithmetic.
 */
class JpaAdaptersTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:37Z");
    private static final UUID PRINCIPAL = UUID.randomUUID();

    private static String hash() {
        String hex = Integer.toHexString(ThreadLocalRandom.current().nextInt());
        return (hex + "0".repeat(9)).substring(0, 8) + "0".repeat(56);
    }

    // ---- api keys ------------------------------------------------------

    @Test
    void apiKeySaveInsertsNewEntitiesAndMapsBack() {
        ApiKeyJpaRepository jpa = mock(ApiKeyJpaRepository.class);
        when(jpa.findById(any())).thenReturn(Optional.empty());
        when(jpa.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        JpaApiKeyRepository repository = new JpaApiKeyRepository(jpa);

        ApiKey key = ApiKey.active("key_000000000000000000001", PRINCIPAL, hash(),
                java.util.Set.of(Scope.PAYMENTS_READ, Scope.PAYMENTS_WRITE), 120, 5_000L, NOW);
        ApiKey stored = repository.save(key);

        assertEquals(key, stored);
        ArgumentCaptor<ApiKeyEntity> captor = ArgumentCaptor.forClass(ApiKeyEntity.class);
        verify(jpa).save(captor.capture());
        ApiKeyEntity entity = captor.getValue();
        assertEquals("key_000000000000000000001", entity.getId());
        assertEquals(PRINCIPAL, entity.getPrincipalId());
        assertEquals(key.secretHash(), entity.getSecretHash());
        // scopes are stored sorted, comma-joined
        assertEquals("payments:read,payments:write", entity.getScopes());
        assertEquals("ACTIVE", entity.getStatus());
        assertEquals(120, entity.getRpmLimit());
        assertEquals(5_000L, entity.getMonthlyLimit());
        assertNull(entity.getGraceExpiresAt());
        // round trip
        assertEquals(key, entity.toDomain());
        when(jpa.findById("key_000000000000000000001")).thenReturn(Optional.of(entity));
        assertEquals(key, repository.findById("key_000000000000000000001").orElseThrow());
        assertTrue(entity.toString().contains("key_000000000000000000001"));
        // all accessors
        assertEquals(NOW, entity.getCreatedAt());
        assertEquals(NOW, entity.getUpdatedAt());
    }

    @Test
    void apiKeySaveUpdatesLifecycleFieldsOnExistingRows() {
        ApiKeyEntity existing = ApiKeyEntity.fromDomain(ApiKey.active(
                "key_000000000000000000002", PRINCIPAL, hash(),
                java.util.Set.of(Scope.WALLETS_READ), 60, 600L, NOW));
        ApiKeyJpaRepository jpa = mock(ApiKeyJpaRepository.class);
        when(jpa.findById("key_000000000000000000002")).thenReturn(Optional.of(existing));
        when(jpa.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        JpaApiKeyRepository repository = new JpaApiKeyRepository(jpa);

        ApiKey rotated = repository.save(existing.toDomain().markRotating(NOW));
        assertEquals(ApiKeyStatus.ROTATING, rotated.status());
        assertEquals(NOW.plus(Duration.ofHours(24)), rotated.graceExpiresAt());
        // the scopes and quotas never change on a rotation
        assertEquals(java.util.Set.of(Scope.WALLETS_READ), rotated.scopes());
        assertEquals(60, rotated.rpmLimit());
        assertEquals(600L, rotated.monthlyLimit());
        verify(jpa).save(existing); // the SAME managed instance, updated in place
    }

    @Test
    void apiKeyLookupAndListingDelegates() {
        ApiKeyJpaRepository jpa = mock(ApiKeyJpaRepository.class);
        ApiKey first = ApiKey.active("key_000000000000000000003", PRINCIPAL, hash(),
                java.util.Set.of(Scope.OPS_READ), 60, 600L, NOW);
        ApiKey second = ApiKey.active("key_000000000000000000004", PRINCIPAL, hash(),
                java.util.Set.of(Scope.OPS_READ), 60, 600L, NOW);
        when(jpa.findBySecretHash(second.secretHash()))
                .thenReturn(Optional.of(ApiKeyEntity.fromDomain(second)));
        when(jpa.findByPrincipalIdOrderByIdAsc(PRINCIPAL))
                .thenReturn(List.of(ApiKeyEntity.fromDomain(first),
                        ApiKeyEntity.fromDomain(second)));
        JpaApiKeyRepository repository = new JpaApiKeyRepository(jpa);

        assertEquals(Optional.empty(), repository.findByHash(first.secretHash()));
        assertEquals(second, repository.findByHash(second.secretHash()).orElseThrow());

        assertEquals(List.of(first, second), repository.listByPrincipal(PRINCIPAL, 50, null));
        assertEquals(List.of(second), repository.listByPrincipal(PRINCIPAL, 50,
                "key_000000000000000000003"));
        assertEquals(List.of(), repository.listByPrincipal(PRINCIPAL, 0, null));
    }

    // ---- webhook subscriptions -----------------------------------------

    @Test
    void subscriptionSaveInsertsUpdatesAndSoftDeletes() {
        WebhookSubscriptionJpaRepository jpa = mock(WebhookSubscriptionJpaRepository.class);
        when(jpa.findById(any())).thenReturn(Optional.empty());
        when(jpa.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        JpaWebhookSubscriptionRepository repository = new JpaWebhookSubscriptionRepository(jpa);

        WebhookSubscription subscription = WebhookSubscription.active("wh_000000000000000000001",
                PRINCIPAL, "https://merchant.example.com/hooks",
                java.util.Set.of(com.sharkpay.gateway.domain.EventPattern.of("payment.*"),
                        com.sharkpay.gateway.domain.EventPattern.of("payout.created")),
                "whsec_0123456789abcdef", NOW);
        WebhookSubscription stored = repository.save(subscription);
        assertEquals(subscription, stored);

        // event patterns are stored sorted and round-trip
        WebhookSubscriptionEntity entity = WebhookSubscriptionEntity.fromDomain(subscription);
        assertEquals("payment.*,payout.created", entity.getEventPatterns());
        assertEquals(subscription, entity.toDomain());

        // update path: pausing reuses the managed entity (applyDomain)
        when(jpa.findById("wh_000000000000000000001")).thenReturn(Optional.of(entity));
        repository.save(subscription.paused(NOW));
        assertEquals("PAUSED", entity.getState());
        assertEquals(NOW, entity.getUpdatedAt());

        // soft delete: hidden from listings, findable by id
        WebhookSubscription deleted = subscription.deleted(NOW);
        when(jpa.findByPrincipalIdOrderByIdAsc(PRINCIPAL))
                .thenReturn(List.of(WebhookSubscriptionEntity.fromDomain(deleted)));
        when(jpa.findByStateOrderByIdAsc("ACTIVE"))
                .thenReturn(List.of(WebhookSubscriptionEntity.fromDomain(subscription)));
        when(jpa.findById("wh_000000000000000000001"))
                .thenReturn(Optional.of(WebhookSubscriptionEntity.fromDomain(deleted)));
        assertEquals(List.of(), repository.listByPrincipal(PRINCIPAL, 50, null));
        assertEquals(List.of(subscription), repository.listActive());
        assertEquals(SubscriptionState.DELETED, repository.findById(
                "wh_000000000000000000001").orElseThrow().state());
        assertTrue(entity.toString().contains("wh_000000000000000000001"));
        // accessors
        assertEquals("https://merchant.example.com/hooks", entity.getUrl());
        assertEquals("whsec_0123456789abcdef", entity.getSigningSecret());
        assertEquals(0, entity.getConsecutiveDeadDeliveries());
        assertEquals(NOW, entity.getCreatedAt());
    }

    // ---- webhook deliveries --------------------------------------------

    @Test
    void deliverySaveMapsBothWaysAndLookupsDelegate() {
        WebhookDeliveryJpaRepository jpa = mock(WebhookDeliveryJpaRepository.class);
        when(jpa.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        JpaWebhookDeliveryRepository repository = new JpaWebhookDeliveryRepository(jpa);

        WebhookDelivery delivery = WebhookDelivery.pending("whd_0000000000000000001",
                "wh_000000000000000000001", "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d",
                "payment.succeeded", "{\"id\":\"x\"}", NOW);
        assertEquals(delivery, repository.save(delivery));

        ArgumentCaptor<WebhookDeliveryEntity> captor =
                ArgumentCaptor.forClass(WebhookDeliveryEntity.class);
        verify(jpa).save(captor.capture());
        WebhookDeliveryEntity entity = captor.getValue();
        assertEquals("whd_0000000000000000001", entity.getId());
        assertEquals("wh_000000000000000000001", entity.getSubscriptionId());
        assertEquals("0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d", entity.getEventId());
        assertEquals("payment.succeeded", entity.getEventType());
        assertEquals("{\"id\":\"x\"}", entity.getPayload());
        assertEquals("PENDING", entity.getState());
        assertEquals(0, entity.getAttemptCount());
        assertEquals(NOW, entity.getNextAttemptAt());
        assertNull(entity.getDeliveredAt());
        assertEquals(delivery, entity.toDomain());
        assertTrue(entity.toString().contains("whd_0000000000000000001"));

        when(jpa.findById("whd_0000000000000000001")).thenReturn(Optional.of(entity));
        when(jpa.findBySubscriptionIdAndEventId("wh_000000000000000000001",
                "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d"))
                .thenReturn(Optional.of(entity));
        assertEquals(Optional.of(delivery), repository.findById("whd_0000000000000000001"));
        assertEquals(Optional.of(delivery), repository.findBySubscriptionAndEvent(
                "wh_000000000000000000001", "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d"));
    }

    @Test
    void dueSelectionReadsPendingOldestFirstAndFiltersByDueAt() {
        WebhookDeliveryJpaRepository jpa = mock(WebhookDeliveryJpaRepository.class);
        // due now: created 60 s ago, never attempted
        WebhookDelivery dueNow = WebhookDelivery.pending("whd_0000000000000000002",
                "wh_000000000000000000001", "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d",
                "payment.succeeded", "{}", NOW.minusSeconds(60));
        // not due: failed once 30 s ago → next attempt is 30 s in the future
        WebhookDelivery notYet = WebhookDelivery.pending("whd_0000000000000000003",
                "wh_000000000000000000001", "0192a7c6-2b3c-7d4e-9f5a-8b6c7d8e9f0a",
                "payment.failed", "{}", NOW.minusSeconds(30))
                .attemptFailed(500, "http 500", NOW.minusSeconds(30));
        assertEquals(NOW.plusSeconds(30), notYet.nextAttemptAt());

        when(jpa.findByStateOrderByNextAttemptAtAsc("PENDING"))
                .thenReturn(List.of(WebhookDeliveryEntity.fromDomain(dueNow),
                        WebhookDeliveryEntity.fromDomain(notYet)));
        JpaWebhookDeliveryRepository repository = new JpaWebhookDeliveryRepository(jpa);

        assertEquals(List.of(dueNow), repository.findDue(NOW, 50));
        // at the scheduled instant the retry becomes due too
        assertEquals(List.of(dueNow, notYet), repository.findDue(NOW.plusSeconds(30), 50));
        assertEquals(List.of(), repository.findDue(NOW, 0));
    }

    @Test
    void deliveryListingIsNewestFirstWithAnIdCursor() {
        WebhookDeliveryJpaRepository jpa = mock(WebhookDeliveryJpaRepository.class);
        WebhookDelivery older = WebhookDelivery.pending("whd_0000000000000000004",
                "wh_000000000000000000001", "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d",
                "payment.succeeded", "{}", NOW.minusSeconds(120));
        WebhookDelivery newer = WebhookDelivery.pending("whd_0000000000000000005",
                "wh_000000000000000000001", "0192a7c6-2b3c-7d4e-9f5a-8b6c7d8e9f0a",
                "payment.failed", "{}", NOW.minusSeconds(60));
        when(jpa.findBySubscriptionId("wh_000000000000000000001"))
                .thenReturn(List.of(WebhookDeliveryEntity.fromDomain(older),
                        WebhookDeliveryEntity.fromDomain(newer)));
        JpaWebhookDeliveryRepository repository = new JpaWebhookDeliveryRepository(jpa);

        assertEquals(List.of(newer, older), repository.listBySubscription(
                "wh_000000000000000000001", 50, null));
        // cursor after the first page entry (newer) pages to the older one
        assertEquals(List.of(older), repository.listBySubscription(
                "wh_000000000000000000001", 50, newer.id()));
        assertEquals(List.of(), repository.listBySubscription(
                "wh_000000000000000000001", 50, "whd_missing"));
        assertEquals(List.of(newer), repository.listBySubscription(
                "wh_000000000000000000001", 1, null));
    }

    // ---- quotas ---------------------------------------------------------

    @Test
    void quotaStoreConsumesWithinTheWindowAndCreatesBucketsOnDemand() {
        QuotaBucketJpaRepository jpa = mock(QuotaBucketJpaRepository.class);
        when(jpa.findById(any())).thenReturn(Optional.empty());
        when(jpa.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        JpaQuotaStore store = new JpaQuotaStore(jpa);

        QuotaDecision first = store.checkAndConsume("key_1", 2, 10L, NOW);
        assertTrue(first.allowed());

        // same window: the minute bucket is reused (one row, incremented)
        QuotaBucketEntity minute = new QuotaBucketEntity("key_1", "MINUTE",
                QuotaWindows.minuteStart(NOW), QuotaWindows.minuteEnd(NOW), NOW);
        minute.setUsed(1);
        when(jpa.findById(new QuotaBucketId("key_1", "MINUTE", QuotaWindows.minuteStart(NOW))))
                .thenReturn(Optional.of(minute));
        QuotaDecision second = store.checkAndConsume("key_1", 2, 10L, NOW);
        assertTrue(second.allowed());
        assertEquals(2, minute.getUsed());

        // third call in the same minute: exceeded, nothing consumed
        QuotaDecision third = store.checkAndConsume("key_1", 2, 10L, NOW);
        assertFalse(third.allowed());
        assertFalse(third.monthly());
        assertTrue(third.retryAfter().isPresent());
        assertTrue(third.retryAfter().getAsLong() >= 1);
        assertEquals(2, minute.getUsed()); // unchanged on rejection

        // a new minute rolls to a new bucket
        when(jpa.findById(any())).thenReturn(Optional.empty());
        QuotaDecision fresh = store.checkAndConsume("key_1", 2, 10L,
                NOW.plusSeconds(60));
        assertTrue(fresh.allowed());

        // the month bucket was created for the calendar month of NOW
        ArgumentCaptor<QuotaBucketEntity> captor = ArgumentCaptor.forClass(QuotaBucketEntity.class);
        verify(jpa, atLeastOnce()).save(captor.capture());
        assertTrue(captor.getAllValues().stream().anyMatch(bucket ->
                "MONTH".equals(bucket.getWindowKind())
                        && QuotaWindows.monthStart(NOW).equals(bucket.getWindowStart())
                        && QuotaWindows.monthEnd(NOW).equals(bucket.getWindowEnd())),
                "a month bucket row must be saved");
    }

    @Test
    void monthlyQuotaExhaustionBlocksEvenInsideAFreshMinute() {
        QuotaBucketJpaRepository jpa = mock(QuotaBucketJpaRepository.class);
        JpaQuotaStore store = new JpaQuotaStore(jpa);

        QuotaBucketEntity month = new QuotaBucketEntity("key_2", "MONTH",
                QuotaWindows.monthStart(NOW), QuotaWindows.monthEnd(NOW), NOW);
        month.setUsed(10);
        when(jpa.findById(any())).thenAnswer(invocation -> {
            QuotaBucketId id = invocation.getArgument(0);
            return "MONTH".equals(id.getWindowKind()) ? Optional.of(month) : Optional.empty();
        });

        QuotaDecision decision = store.checkAndConsume("key_2", 100, 10L, NOW);
        assertFalse(decision.allowed());
        assertTrue(decision.monthly());
        // the minute bucket was NOT persisted: nothing is consumed on a rejection
        verify(jpa, never()).save(any());
    }

    // ---- idempotency cache ----------------------------------------------

    @Test
    void idempotencyCachePutsAndFindsWithCompositeKey() {
        IdempotencyCacheJpaRepository jpa = mock(IdempotencyCacheJpaRepository.class);
        JpaIdempotencyCache cache = new JpaIdempotencyCache(jpa,
                java.time.Clock.fixed(NOW, java.time.ZoneOffset.UTC));

        cache.put("PASSTHROUGH:PAYMENTS", "idem-1",
                IdempotencyCache.CachedResponse.upstream("fingerprint", 201, "{\"id\":1}"));
        ArgumentCaptor<IdempotencyCacheEntity> captor =
                ArgumentCaptor.forClass(IdempotencyCacheEntity.class);
        verify(jpa).save(captor.capture());
        IdempotencyCacheEntity entity = captor.getValue();
        assertEquals("PASSTHROUGH:PAYMENTS", entity.getScope());
        assertEquals("idem-1", entity.getIdempotencyKey());
        assertEquals("fingerprint", entity.getRequestFingerprint());
        assertEquals(201, entity.getStatusCode());
        assertEquals("{\"id\":1}", entity.getResponseBody());
        assertNull(entity.getEntityId());
        assertEquals(NOW, entity.getCreatedAt());
        assertEquals(new IdempotencyCache.CachedResponse("fingerprint", 201, "{\"id\":1}", null),
                entity.toDomain());
        assertTrue(entity.toString().contains("idem-1"));

        when(jpa.findById(new IdempotencyCacheEntityId("PASSTHROUGH:PAYMENTS", "idem-1")))
                .thenReturn(Optional.of(entity));
        Optional<IdempotencyCache.CachedResponse> found =
                cache.find("PASSTHROUGH:PAYMENTS", "idem-1");
        assertTrue(found.isPresent());
        assertEquals("{\"id\":1}", found.orElseThrow().body());
        assertTrue(cache.find("PASSTHROUGH:PAYOUTS", "idem-1").isEmpty());

        // native entries store the entity id instead of a body
        IdempotencyCacheEntity nativeEntity = IdempotencyCacheEntity.fromDomain(
                "CREATE_API_KEY", "idem-2",
                IdempotencyCache.CachedResponse.entity("f", 201, "key_1"), NOW);
        assertEquals("key_1", nativeEntity.getEntityId());
        assertNull(nativeEntity.getResponseBody());
    }

    // ---- composite ids & entity plumbing --------------------------------

    @Test
    void compositeIdsEqualOnTheirKeyFields() {
        QuotaBucketId first = new QuotaBucketId("k", "MINUTE", NOW);
        QuotaBucketId same = new QuotaBucketId("k", "MINUTE", NOW);
        QuotaBucketId other = new QuotaBucketId("k", "MONTH", NOW);
        assertEquals(first, same);
        assertEquals(first.hashCode(), same.hashCode());
        assertFalse(first.equals(other));
        assertFalse(first.equals(null));
        assertFalse(first.equals("k"));
        assertEquals("k", first.getKeyId());
        assertEquals("MINUTE", first.getWindowKind());
        assertEquals(NOW, first.getWindowStart());
        // jpa needs the no-arg constructor + getters
        QuotaBucketId bare = new QuotaBucketId();
        assertNull(bare.getKeyId());
        assertNull(bare.getWindowKind());
        assertNull(bare.getWindowStart());

        IdempotencyCacheEntityId a = new IdempotencyCacheEntityId("S", "k1");
        IdempotencyCacheEntityId b = new IdempotencyCacheEntityId("S", "k1");
        IdempotencyCacheEntityId c = new IdempotencyCacheEntityId("T", "k1");
        assertEquals(a, b);
        assertEquals(a.hashCode(), b.hashCode());
        assertFalse(a.equals(c));
        assertFalse(a.equals(null));
        assertFalse(a.equals("S"));
        assertEquals("S", a.getScope());
        assertEquals("k1", a.getIdempotencyKey());
        assertNull(new IdempotencyCacheEntityId().getScope());
        assertNull(new IdempotencyCacheEntityId().getIdempotencyKey());
    }

    @Test
    void quotaBucketEntityEqualityAndAccessors() {
        QuotaBucketEntity entity = new QuotaBucketEntity("k", "MINUTE", NOW, NOW.plusSeconds(60),
                NOW);
        assertEquals(0, entity.getUsed());
        entity.setUsed(5);
        entity.setUpdatedAt(NOW.plusSeconds(1));
        assertEquals(5, entity.getUsed());
        assertEquals(NOW.plusSeconds(1), entity.getUpdatedAt());
        assertEquals(entity, new QuotaBucketEntity("k", "MINUTE", NOW, NOW.plusSeconds(60), NOW
                .plusSeconds(1)));
        assertEquals(entity.hashCode(), new QuotaBucketEntity("k", "MINUTE", NOW,
                NOW.plusSeconds(60), NOW).hashCode());
        assertTrue(entity.toString().contains("MINUTE"));
        assertEquals("k", entity.getKeyId());
        assertEquals("MINUTE", entity.getWindowKind());
        assertEquals(NOW, entity.getWindowStart());
        assertEquals(NOW.plusSeconds(60), entity.getWindowEnd());
        assertFalse(entity.equals(null));
        assertFalse(entity.equals("k"));
    }
}
