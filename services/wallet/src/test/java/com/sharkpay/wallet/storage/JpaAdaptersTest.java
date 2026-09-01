package com.sharkpay.wallet.storage;

import com.sharkpay.money.Money;
import com.sharkpay.wallet.domain.Direction;
import com.sharkpay.wallet.domain.Hold;
import com.sharkpay.wallet.domain.ProjectionLeg;
import com.sharkpay.wallet.domain.Source;
import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.ports.IdempotencyStore;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Limit;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Covers the JPA port adapters without a database: Spring Data interfaces
 * are replaced by Mockito-backed in-memory fakes with the same semantics as
 * the Flyway schema (PK (wallet_id, posting_id) = leg dedup; unique
 * (scope, idempotency_key)). Verifies delegation + mapping + the ordered
 * balance recompute of the projection adapter.
 */
class JpaAdaptersTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final String WALLET_ID = "wal_0123456789abcdef0123456789abcdef";

    @Test
    void walletAdapterDelegatesAndMaps() {
        WalletJpaRepository jpa = Mockito.mock(WalletJpaRepository.class);
        JpaWalletRepository adapter = new JpaWalletRepository(jpa);
        Wallet wallet = Wallet.newWallet(WALLET_ID, UUID.randomUUID(), "KES", UUID.randomUUID(), T0);

        when(jpa.save(any(WalletEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jpa.findById(WALLET_ID))
                .thenReturn(Optional.of(WalletEntity.fromDomain(wallet)));
        when(jpa.findByPrincipalIdAndCurrency(wallet.principalId(), "KES"))
                .thenReturn(Optional.of(WalletEntity.fromDomain(wallet)));
        when(jpa.findByLedgerAccountId(wallet.ledgerAccountId()))
                .thenReturn(Optional.of(WalletEntity.fromDomain(wallet)));
        when(jpa.findAll(Mockito.any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(WalletEntity.fromDomain(wallet)));

        assertThat(adapter.save(wallet)).isEqualTo(wallet);
        assertThat(adapter.findById(WALLET_ID)).contains(wallet);
        assertThat(adapter.findByPrincipalAndCurrency(wallet.principalId(), "KES"))
                .contains(wallet);
        assertThat(adapter.findByLedgerAccountId(wallet.ledgerAccountId())).contains(wallet);
        assertThat(adapter.list(null, 10, null)).containsExactly(wallet);

        // update path: same id → applyDomain
        Wallet frozen = adapter.findById(WALLET_ID).orElseThrow();
        frozen.freeze("case-1", T0);
        assertThat(adapter.save(frozen)).isEqualTo(frozen);
        verify(jpa, Mockito.times(2)).save(any(WalletEntity.class));

        assertThat(adapter.findById("wal_0123456789abcdef0123456789abcdee")).isEmpty();
    }

    @Test
    void walletAdapterFiltersAndPaginatesInMemory() {
        WalletJpaRepository jpa = Mockito.mock(WalletJpaRepository.class);
        JpaWalletRepository adapter = new JpaWalletRepository(jpa);
        UUID principal = UUID.randomUUID();
        Wallet a = Wallet.newWallet("wal_00000000000000000000000000000001", principal, "KES",
                UUID.randomUUID(), T0);
        Wallet b = Wallet.newWallet("wal_00000000000000000000000000000002", principal, "USD",
                UUID.randomUUID(), T0);
        Wallet c = Wallet.newWallet("wal_00000000000000000000000000000003", UUID.randomUUID(),
                "KES", UUID.randomUUID(), T0);
        c.freeze("case-1", T0);
        when(jpa.findAll(Mockito.any(org.springframework.data.domain.Sort.class)))
                .thenReturn(List.of(WalletEntity.fromDomain(a), WalletEntity.fromDomain(b),
                        WalletEntity.fromDomain(c)));

        assertThat(adapter.list(new com.sharkpay.wallet.ports.WalletRepository.WalletFilter(
                principal, null, null), 10, null)).containsExactly(a, b);
        assertThat(adapter.list(new com.sharkpay.wallet.ports.WalletRepository.WalletFilter(
                null, "KES", null), 10, null)).containsExactly(a, c);
        assertThat(adapter.list(new com.sharkpay.wallet.ports.WalletRepository.WalletFilter(
                null, null, com.sharkpay.wallet.domain.WalletStatus.FROZEN), 10, null))
                .containsExactly(c);
        assertThat(adapter.list(null, 2, "wal_00000000000000000000000000000001"))
                .containsExactly(b, c);
    }

    @Test
    void holdAdapterDelegatesAndMaps() {
        HoldJpaRepository jpa = Mockito.mock(HoldJpaRepository.class);
        JpaHoldRepository adapter = new JpaHoldRepository(jpa);
        Hold hold = Hold.place("hld_0123456789abcdef0123456789abcdef", WALLET_ID,
                Money.of(40_000, "KES"), Source.PAYMENTS, UUID.randomUUID(), "r", T0);

        when(jpa.save(any(HoldEntity.class))).thenAnswer(inv -> inv.getArgument(0));
        when(jpa.findById(hold.id())).thenReturn(Optional.of(HoldEntity.fromDomain(hold)));
        when(jpa.findByWalletIdAndStateOrderByIdAsc(WALLET_ID, "ACTIVE"))
                .thenReturn(List.of(HoldEntity.fromDomain(hold)));

        assertThat(adapter.save(hold)).isEqualTo(hold);
        assertThat(adapter.findById(hold.id())).contains(hold);
        assertThat(adapter.findActiveByWalletId(WALLET_ID)).containsExactly(hold);

        // terminal update path
        Hold captured = adapter.findById(hold.id()).orElseThrow();
        captured.capture(Money.of(1_000, "KES"), T0);
        assertThat(adapter.save(captured)).isEqualTo(captured);
        verify(jpa, Mockito.times(2)).save(any(HoldEntity.class));
    }

    @Test
    void idempotencyAdapterDelegatesAndSwallowsInsertRaces() {
        IdempotencyKeyJpaRepository jpa = Mockito.mock(IdempotencyKeyJpaRepository.class);
        JpaIdempotencyStore adapter = new JpaIdempotencyStore(jpa);
        IdempotencyStore.StoredRequest request = new IdempotencyStore.StoredRequest(
                "PLACE_HOLD|x", "hld_0123456789abcdef0123456789abcdef");

        when(jpa.findById(new IdempotencyKeyPk("PLACE_HOLD", "key-1")))
                .thenReturn(Optional.empty());
        when(jpa.saveAndFlush(any(IdempotencyKeyEntity.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        assertThat(adapter.find(IdempotencyStore.Scope.PLACE_HOLD, "key-1")).isEmpty();
        adapter.put(IdempotencyStore.Scope.PLACE_HOLD, "key-1", request);

        when(jpa.findById(new IdempotencyKeyPk("PLACE_HOLD", "key-1")))
                .thenReturn(Optional.of(new IdempotencyKeyEntity(
                        new IdempotencyKeyPk("PLACE_HOLD", "key-1"), request.requestFingerprint(),
                        request.entityId(), T0)));
        assertThat(adapter.find(IdempotencyStore.Scope.PLACE_HOLD, "key-1")).contains(request);

        // a concurrent duplicate insert is swallowed (the loser replays on retry)
        when(jpa.saveAndFlush(any(IdempotencyKeyEntity.class)))
                .thenThrow(new DataIntegrityViolationException("duplicate key"));
        assertThatCode(() -> adapter.put(IdempotencyStore.Scope.PLACE_HOLD, "key-1", request))
                .doesNotThrowAnyException();

        adapter.remove(IdempotencyStore.Scope.PLACE_HOLD, "key-1");
        verify(jpa).deleteById(new IdempotencyKeyPk("PLACE_HOLD", "key-1"));
    }

    @Test
    void projectionAppliesLegsInOrderAndRecomputesRunningBalances() {
        PostingStore store = new PostingStore();
        JpaProjectionStore adapter = new JpaProjectionStore(store.jpa, store.eventsJpa);

        // delivered out of posting order: 101, 103, then 102
        assertThat(adapter.applyLeg(WALLET_ID, credit(101, 100))).isTrue();
        assertThat(adapter.applyLeg(WALLET_ID, credit(103, 10))).isTrue();
        assertThat(adapter.applyLeg(WALLET_ID, debit(102, 30))).isTrue();

        // the whole statement is recomputed in posting order:
        // 101 → 100, 102 → 70, 103 → 80
        assertThat(adapter.totalMinor(WALLET_ID)).isEqualTo(80L);
        assertThat(adapter.statement(WALLET_ID, 10, null))
                .extracting(line -> line.balanceAfter().amountMinor())
                .containsExactly(100L, 70L, 80L);
        assertThat(adapter.statement(WALLET_ID, 10, null))
                .extracting(line -> line.leg().postingId())
                .containsExactly(101L, 102L, 103L);
    }

    @Test
    void projectionDuplicateLegsAreNoOps() {
        PostingStore store = new PostingStore();
        JpaProjectionStore adapter = new JpaProjectionStore(store.jpa, store.eventsJpa);

        assertThat(adapter.applyLeg(WALLET_ID, credit(101, 100))).isTrue();
        // the same (wallet, posting) pair — the PK is the dedup
        assertThat(adapter.applyLeg(WALLET_ID, credit(101, 999))).isFalse();

        assertThat(adapter.totalMinor(WALLET_ID)).isEqualTo(100L);
        // only the FIRST (successful) insert triggered the ordered recompute;
        // the duplicate applied nothing — no further recompute saves
        assertThat(store.savedEntiesExcludingInitialInsert()).isEqualTo(1);
    }

    @Test
    void projectionStatementPaginatesAndTotalsEmptyWallets() {
        PostingStore store = new PostingStore();
        JpaProjectionStore adapter = new JpaProjectionStore(store.jpa, store.eventsJpa);
        adapter.applyLeg(WALLET_ID, credit(101, 100));
        adapter.applyLeg(WALLET_ID, credit(102, 50));
        adapter.applyLeg(WALLET_ID, credit(103, 25));

        assertThat(adapter.statement(WALLET_ID, 2, null))
                .extracting(line -> line.leg().postingId())
                .containsExactly(101L, 102L);
        assertThat(adapter.statement(WALLET_ID, 2, 102L))
                .extracting(line -> line.leg().postingId())
                .containsExactly(103L);
        assertThat(adapter.totalMinor("wal_00000000000000000000000000000099")).isZero();
        assertThat(adapter.statement("wal_00000000000000000000000000000099", 10, null)).isEmpty();
    }

    @Test
    void projectionEventDedupIsIdempotent() {
        PostingStore store = new PostingStore();
        JpaProjectionStore adapter = new JpaProjectionStore(store.jpa, store.eventsJpa);
        UUID entryId = UUID.randomUUID();

        assertThat(adapter.isEventApplied("event-1")).isFalse();
        adapter.markEventApplied("event-1", entryId);
        adapter.markEventApplied("event-1", entryId);   // idempotent
        assertThat(adapter.isEventApplied("event-1")).isTrue();
        assertThat(adapter.isEventApplied(null)).isFalse();
        assertThat(store.eventsJapSavedCount()).isEqualTo(1);
    }

    @Test
    void projectionRejectsCurrencyMismatchesLikeTheDomain() {
        PostingStore store = new PostingStore();
        JpaProjectionStore adapter = new JpaProjectionStore(store.jpa, store.eventsJpa);
        // the sequence's currency is pinned by the first leg
        adapter.applyLeg(WALLET_ID, credit(101, 100));

        assertThatThrownBy(() -> adapter.applyLeg(WALLET_ID, new ProjectionLeg(102,
                UUID.randomUUID(), "capture", Direction.CREDIT, Money.of(5, "USD"),
                Source.PAYMENTS, UUID.randomUUID(), null, T0)))
                .isInstanceOf(com.sharkpay.wallet.domain.ProjectionInconsistencyException.class)
                .hasMessageContaining("does not match wallet");
    }

    // ------------------------------------------------------------------
    // test doubles
    // ------------------------------------------------------------------

    private static ProjectionLeg credit(long postingId, long amountMinor) {
        return new ProjectionLeg(postingId, UUID.randomUUID(), "capture", Direction.CREDIT,
                Money.of(amountMinor, "KES"), Source.PAYMENTS, UUID.randomUUID(), null, T0);
    }

    private static ProjectionLeg debit(long postingId, long amountMinor) {
        return new ProjectionLeg(postingId, UUID.randomUUID(), "hold", Direction.DEBIT,
                Money.of(amountMinor, "KES"), Source.PAYOUTS, UUID.randomUUID(), "settled", T0);
    }

    /** In-memory WalletPostingJpaRepository with the schema's semantics. */
    private static final class PostingStore {

        final WalletPostingJpaRepository jpa = Mockito.mock(WalletPostingJpaRepository.class);
        final AppliedLedgerEventJpaRepository eventsJpa =
                Mockito.mock(AppliedLedgerEventJpaRepository.class);
        private final Map<WalletPostingId, WalletPostingEntity> rows = new LinkedHashMap<>();
        private final Map<String, AppliedLedgerEventEntity> events = new ConcurrentHashMap<>();
        private int recomputeSaves = 0;
        private int eventSaves = 0;

        PostingStore() {
            when(jpa.saveAndFlush(any(WalletPostingEntity.class))).thenAnswer(inv -> {
                WalletPostingEntity entity = inv.getArgument(0);
                if (rows.containsKey(entity.getId())) {
                    throw new DataIntegrityViolationException("duplicate (wallet, posting)");
                }
                rows.put(entity.getId(), entity);
                return entity;
            });
            when(jpa.save(any(WalletPostingEntity.class))).thenAnswer(inv -> {
                WalletPostingEntity entity = inv.getArgument(0);
                rows.put(entity.getId(), entity);
                recomputeSaves++;
                return entity;
            });
            when(jpa.findAllOrdered(anyString())).thenAnswer(inv -> {
                String walletId = inv.getArgument(0);
                return rows.values().stream()
                        .filter(entity -> entity.getId().getWalletId().equals(walletId))
                        .sorted(Comparator.comparingLong(entity -> entity.getId().getPostingId()))
                        .toList();
            });
            when(jpa.findLast(anyString(), any(Limit.class))).thenAnswer(inv -> {
                String walletId = inv.getArgument(0);
                List<WalletPostingEntity> ordered = new ArrayList<>(rows.values().stream()
                        .filter(entity -> entity.getId().getWalletId().equals(walletId))
                        .sorted(Comparator.comparingLong(entity -> entity.getId().getPostingId()))
                        .toList());
                java.util.Collections.reverse(ordered);
                return ordered.stream().limit(1).toList();
            });
            when(jpa.findNext(anyString(), anyLong(), any(Limit.class))).thenAnswer(inv -> {
                String walletId = inv.getArgument(0);
                long after = inv.getArgument(1);
                Limit limit = inv.getArgument(2);
                return rows.values().stream()
                        .filter(entity -> entity.getId().getWalletId().equals(walletId))
                        .filter(entity -> entity.getId().getPostingId() > after)
                        .sorted(Comparator.comparingLong(entity -> entity.getId().getPostingId()))
                        .limit(Math.max(0, limit.max()))
                        .toList();
            });
            when(eventsJpa.existsById(anyString()))
                    .thenAnswer(inv -> events.containsKey(inv.getArgument(0)));
            Mockito.doAnswer(inv -> {
                AppliedLedgerEventEntity entity = inv.getArgument(0);
                events.put(entity.getEventId(), entity);
                eventSaves++;
                return entity;
            }).when(eventsJpa).save(any(AppliedLedgerEventEntity.class));
        }

        int savedEntiesExcludingInitialInsert() {
            return recomputeSaves;
        }

        int eventsJapSavedCount() {
            return eventSaves;
        }
    }
}
