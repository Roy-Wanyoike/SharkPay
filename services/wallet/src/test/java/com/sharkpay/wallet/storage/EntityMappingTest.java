package com.sharkpay.wallet.storage;

import com.sharkpay.money.Money;
import com.sharkpay.wallet.domain.Direction;
import com.sharkpay.wallet.domain.Hold;
import com.sharkpay.wallet.domain.HoldState;
import com.sharkpay.wallet.domain.ProjectionLeg;
import com.sharkpay.wallet.domain.Source;
import com.sharkpay.wallet.domain.StatementLine;
import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.ports.IdempotencyStore;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class EntityMappingTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-02T11:01:31Z");
    private static final String WALLET_ID = "wal_0123456789abcdef0123456789abcdef";

    @Test
    void walletRoundTripPreservesAllFields() {
        Wallet active = Wallet.newWallet(WALLET_ID, UUID.randomUUID(), "kes",
                UUID.randomUUID(), T0);

        assertThat(WalletEntity.fromDomain(active).toDomain()).isEqualTo(active);

        Wallet frozen = Wallet.newWallet(WALLET_ID, UUID.randomUUID(), "USDC",
                UUID.randomUUID(), T0);
        frozen.freeze("case-77", T1);

        assertThat(WalletEntity.fromDomain(frozen).toDomain()).isEqualTo(frozen);
    }

    @Test
    void walletEntityApplyDomainRefreshesTheLifecycleFields() {
        WalletEntity entity = WalletEntity.fromDomain(
                Wallet.newWallet(WALLET_ID, UUID.randomUUID(), "KES", UUID.randomUUID(), T0));
        Wallet frozen = entity.toDomain();
        frozen.freeze("case-9", T1);

        entity.applyDomain(frozen);

        assertThat(entity.getStatus()).isEqualTo("FROZEN");
        assertThat(entity.getStatusReason()).isEqualTo("case-9");
        assertThat(entity.getStatusChangedAt()).isEqualTo(T1);
        assertThat(entity.getUpdatedAt()).isEqualTo(T1);
        assertThat(entity.getCreatedAt()).isEqualTo(T0);
        assertThat(entity.toDomain()).isEqualTo(frozen);
    }

    @Test
    void holdRoundTripActiveAndTerminal() {
        Hold active = Hold.place("hld_0123456789abcdef0123456789abcdef", WALLET_ID,
                Money.of(40_000, "KES"), Source.PAYMENTS, UUID.randomUUID(), "risk", T0);
        assertThat(HoldEntity.fromDomain(active).toDomain()).isEqualTo(active);

        Hold captured = Hold.place("hld_0123456789abcdef0123456789abcdef", WALLET_ID,
                Money.of(40_000, "KES"), Source.PAYMENTS, UUID.randomUUID(), null, T0);
        captured.capture(Money.of(15_000, "KES"), T1);
        HoldEntity entity = HoldEntity.fromDomain(captured);
        assertThat(entity.toDomain()).isEqualTo(captured);
        assertThat(entity.getCapturedMinor()).isEqualTo(15_000);
        assertThat(entity.getReleasedMinor()).isEqualTo(25_000);

        // refresh path
        Hold released = Hold.place("hld_0123456789abcdef0123456789abcdef", WALLET_ID,
                Money.of(40_000, "KES"), Source.PAYMENTS, UUID.randomUUID(), null, T0);
        released.release(T1);
        HoldEntity releasedEntity = HoldEntity.fromDomain(released);
        Hold terminal = Hold.place("hld_0123456789abcdef0123456789abcdef", WALLET_ID,
                Money.of(40_000, "KES"), Source.PAYMENTS, UUID.randomUUID(), null, T0);
        terminal.release(T1);
        releasedEntity.applyDomain(terminal);
        assertThat(releasedEntity.getState()).isEqualTo(HoldState.RELEASED.name());
        assertThat(releasedEntity.getReleasedMinor()).isEqualTo(40_000);
    }

    @Test
    void walletPostingRoundTripPreservesTheLeg() {
        ProjectionLeg leg = new ProjectionLeg(10_241L, UUID.randomUUID(), "capture",
                Direction.CREDIT, Money.of(150_000, "KES"), Source.PAYMENTS, UUID.randomUUID(),
                "settled", T0);

        WalletPostingEntity entity = WalletPostingEntity.fromLeg(WALLET_ID, leg);

        assertThat(entity.getId().getWalletId()).isEqualTo(WALLET_ID);
        assertThat(entity.getId().getPostingId()).isEqualTo(10_241L);
        assertThat(entity.getCurrency()).isEqualTo("KES");
        assertThat(entity.getAmountMinor()).isEqualTo(150_000);
        assertThat(entity.getBalanceAfter()).isZero();   // filled by the adapter
        assertThat(entity.getRecordedAt()).isNotNull();

        entity.setBalanceAfter(150_000L);
        StatementLine line = entity.toDomain();
        assertThat(line.leg()).isEqualTo(leg);
        assertThat(line.balanceAfter()).isEqualTo(Money.of(150_000, "KES"));
        assertThat(entity.toLeg()).isEqualTo(leg);
    }

    @Test
    void idempotencyKeyEntityCarriesScopeKeyAndFingerprint() {
        IdempotencyKeyEntity entity = new IdempotencyKeyEntity(
                new IdempotencyKeyPk("PLACE_HOLD", "key-42"),
                "PLACE_HOLD|wal_x|500|KES|payments|ref|", "hld_0123456789abcdef0123456789abcdef",
                T0);

        assertThat(entity.getId().getScope()).isEqualTo("PLACE_HOLD");
        assertThat(entity.getId().getIdempotencyKey()).isEqualTo("key-42");
        assertThat(entity.getRequestFingerprint()).startsWith("PLACE_HOLD|");
        assertThat(entity.getEntityId()).startsWith("hld_");
        assertThat(entity.getCreatedAt()).isEqualTo(T0);
    }

    @Test
    void appliedLedgerEventEntityCarriesTheDedupLog() {
        UUID entryId = UUID.randomUUID();
        AppliedLedgerEventEntity entity = new AppliedLedgerEventEntity("event-1", entryId, T0);

        assertThat(entity.getEventId()).isEqualTo("event-1");
        assertThat(entity.getEntryId()).isEqualTo(entryId);
        assertThat(entity.getAppliedAt()).isEqualTo(T0);
    }

    @Test
    void storedRequestValidatesItsFields() {
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new IdempotencyStore.StoredRequest(" ", "wal_x"))
                .isInstanceOf(IllegalArgumentException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new IdempotencyStore.StoredRequest("fp", null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void compositeKeysImplementEqualityAndHashing() {
        IdempotencyKeyPk a = new IdempotencyKeyPk("PLACE_HOLD", "key-1");
        IdempotencyKeyPk b = new IdempotencyKeyPk("PLACE_HOLD", "key-1");
        IdempotencyKeyPk differentKey = new IdempotencyKeyPk("PLACE_HOLD", "key-2");
        IdempotencyKeyPk differentScope = new IdempotencyKeyPk("RELEASE_HOLD", "key-1");

        assertThat(a).isEqualTo(b).hasSameHashCodeAs(b);
        assertThat(a).isNotEqualTo(differentKey).isNotEqualTo(differentScope);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new IdempotencyKeyPk(null, "key"))
                .isInstanceOf(NullPointerException.class);
        org.assertj.core.api.Assertions.assertThatThrownBy(
                        () -> new IdempotencyKeyPk("SCOPE", null))
                .isInstanceOf(NullPointerException.class);

        WalletPostingId postingA = new WalletPostingId(WALLET_ID, 101L);
        WalletPostingId postingB = new WalletPostingId(WALLET_ID, 101L);
        WalletPostingId differentPosting = new WalletPostingId(WALLET_ID, 102L);
        assertThat(postingA).isEqualTo(postingB).hasSameHashCodeAs(postingB);
        assertThat(postingA).isNotEqualTo(differentPosting);
    }
}
