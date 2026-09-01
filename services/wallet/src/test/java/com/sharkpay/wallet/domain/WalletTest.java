package com.sharkpay.wallet.domain;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WalletTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant T1 = Instant.parse("2026-09-01T11:00:00Z");
    private static final UUID PRINCIPAL = UUID.randomUUID();
    private static final UUID ACCOUNT = UUID.randomUUID();

    @Test
    void newWalletIsActiveWithCanonicalCurrency() {
        Wallet wallet = Wallet.newWallet("wal_0123456789abcdef0123456789abcdef",
                PRINCIPAL, "kes", ACCOUNT, T0);

        assertThat(wallet.id()).isEqualTo("wal_0123456789abcdef0123456789abcdef");
        assertThat(wallet.principalId()).isEqualTo(PRINCIPAL);
        assertThat(wallet.currency()).isEqualTo("KES");
        assertThat(wallet.ledgerAccountId()).isEqualTo(ACCOUNT);
        assertThat(wallet.status()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(wallet.isActive()).isTrue();
        assertThat(wallet.statusReason()).isNull();
        assertThat(wallet.statusChangedAt()).isNull();
        assertThat(wallet.createdAt()).isEqualTo(T0);
        assertThat(wallet.updatedAt()).isEqualTo(T0);
    }

    @Test
    void freezeRecordsAuditReasonAndTimestamp() {
        Wallet wallet = active();

        wallet.freeze("compliance case-42", T1);

        assertThat(wallet.status()).isEqualTo(WalletStatus.FROZEN);
        assertThat(wallet.isActive()).isFalse();
        assertThat(wallet.statusReason()).isEqualTo("compliance case-42");
        assertThat(wallet.statusChangedAt()).isEqualTo(T1);
        assertThat(wallet.updatedAt()).isEqualTo(T1);
    }

    @Test
    void unfreezeReturnsToActiveWithNewAuditReason() {
        Wallet wallet = active();
        wallet.freeze("case-1", T1);

        wallet.unfreeze("case-1 cleared", T1);

        assertThat(wallet.status()).isEqualTo(WalletStatus.ACTIVE);
        assertThat(wallet.statusReason()).isEqualTo("case-1 cleared");
    }

    @Test
    void freezingAFrozenWalletIsRejected() {
        Wallet wallet = active();
        wallet.freeze("case-1", T1);

        assertThatThrownBy(() -> wallet.freeze("again", T1))
                .isInstanceOf(WalletStateException.class)
                .hasMessageContaining("freeze a frozen wallet");
        assertThat(wallet.status()).isEqualTo(WalletStatus.FROZEN);
    }

    @Test
    void unfreezingAnActiveWalletIsRejected() {
        Wallet wallet = active();

        assertThatThrownBy(() -> wallet.unfreeze("why", T1))
                .isInstanceOf(WalletStateException.class)
                .hasMessageContaining("unfreeze an active wallet");
        assertThat(wallet.status()).isEqualTo(WalletStatus.ACTIVE);
    }

    @Test
    void freezeRequiresANonBlankReason() {
        Wallet wallet = active();
        assertThatThrownBy(() -> wallet.freeze("   ", T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("audit reason");
        assertThat(wallet.status()).isEqualTo(WalletStatus.ACTIVE);
    }

    @Test
    void freezeRejectsOverlyLongReasons() {
        Wallet wallet = active();
        assertThatThrownBy(() -> wallet.freeze("r".repeat(513), T1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void idsMustMatchTheContractPattern() {
        assertThatThrownBy(() -> Wallet.newWallet("wal_short", PRINCIPAL, "KES", ACCOUNT, T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wal_");
        assertThatThrownBy(() -> Wallet.newWallet("usr_0123456789abcdef0123456789abcdef",
                PRINCIPAL, "KES", ACCOUNT, T0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> Wallet.newWallet(null, PRINCIPAL, "KES", ACCOUNT, T0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unsupportedCurrenciesAreRejected() {
        assertThatThrownBy(() -> Wallet.newWallet("wal_0123456789abcdef0123456789abcdef",
                PRINCIPAL, "XYZ", ACCOUNT, T0))
                .isInstanceOf(com.sharkpay.money.UnknownCurrencyException.class);
    }

    @Test
    void nullPrincipalAndAccountAreRejected() {
        assertThatThrownBy(() -> Wallet.newWallet("wal_0123456789abcdef0123456789abcdef",
                null, "KES", ACCOUNT, T0))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> Wallet.newWallet("wal_0123456789abcdef0123456789abcdef",
                PRINCIPAL, "KES", null, T0))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void equalityIsById() {
        Wallet a = active();
        Wallet b = Wallet.newWallet(a.id(), UUID.randomUUID(), "USD", UUID.randomUUID(), T1);
        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
        assertThat(a).isNotSameAs(b);
    }

    @Test
    void toStringCarriesTheIdentity() {
        Wallet wallet = active();
        assertThat(wallet.toString()).contains(wallet.id()).contains("KES").contains("active");
    }

    private static Wallet active() {
        return Wallet.newWallet("wal_0123456789abcdef0123456789abcdef", PRINCIPAL, "KES", ACCOUNT, T0);
    }
}
