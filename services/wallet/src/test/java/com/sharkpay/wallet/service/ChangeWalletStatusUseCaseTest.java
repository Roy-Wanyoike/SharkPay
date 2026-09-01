package com.sharkpay.wallet.service;

import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.domain.WalletStateException;
import com.sharkpay.wallet.events.WalletEvents;
import com.sharkpay.wallet.testsupport.WalletTestEnv;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ChangeWalletStatusUseCaseTest {

    private final WalletTestEnv env = new WalletTestEnv();

    @Test
    void freezesWithAuditReasonAndPublishesStateChange() {
        Wallet wallet = env.newWallet("KES");

        Wallet frozen = env.changeStatus.freeze(wallet.id(), "compliance case-77");

        assertThat(frozen.status().wireName()).isEqualTo("frozen");
        assertThat(frozen.statusReason()).isEqualTo("compliance case-77");
        Wallet stored = env.wallets.findById(wallet.id()).orElseThrow();
        assertThat(stored.status().wireName()).isEqualTo("frozen");
        assertThat(stored.statusReason()).isEqualTo("compliance case-77");

        assertThat(env.events.events()).hasSize(2);   // creation + freeze
        WalletEvents.WalletStateData data =
                (WalletEvents.WalletStateData) env.events.last().data();
        assertThat(data.from_status()).isEqualTo("active");
        assertThat(data.to_status()).isEqualTo("frozen");
        assertThat(data.reason()).isEqualTo("compliance case-77");
    }

    @Test
    void unfreezesBackToActive() {
        Wallet wallet = env.newWallet("KES");
        env.changeStatus.freeze(wallet.id(), "case-1");

        Wallet active = env.changeStatus.unfreeze(wallet.id(), "case-1 cleared");

        assertThat(active.status().wireName()).isEqualTo("active");
        assertThat(active.statusReason()).isEqualTo("case-1 cleared");
        WalletEvents.WalletStateData data =
                (WalletEvents.WalletStateData) env.events.last().data();
        assertThat(data.from_status()).isEqualTo("frozen");
        assertThat(data.to_status()).isEqualTo("active");
    }

    @Test
    void transitionsAdvanceTheAuditTimestamps() {
        Wallet wallet = env.newWallet("KES");
        env.clock.advance(Duration.ofMinutes(5));

        Wallet frozen = env.changeStatus.freeze(wallet.id(), "case-1");

        assertThat(frozen.statusChangedAt()).isEqualTo(env.clock.instant());
        assertThat(frozen.updatedAt()).isEqualTo(env.clock.instant());
        assertThat(frozen.createdAt()).isBefore(frozen.updatedAt());
    }

    @Test
    void doubleFreezeAndDoubleUnfreezeAreConflicts() {
        Wallet wallet = env.newWallet("KES");
        env.changeStatus.freeze(wallet.id(), "case-1");
        assertThatThrownBy(() -> env.changeStatus.freeze(wallet.id(), "again"))
                .isInstanceOf(WalletStateException.class)
                .hasMessageContaining("freeze a frozen wallet");

        env.changeStatus.unfreeze(wallet.id(), "cleared");
        assertThatThrownBy(() -> env.changeStatus.unfreeze(wallet.id(), "again"))
                .isInstanceOf(WalletStateException.class)
                .hasMessageContaining("unfreeze an active wallet");
    }

    @Test
    void blankOrMissingReasonIsRejected() {
        Wallet wallet = env.newWallet("KES");
        assertThatThrownBy(() -> env.changeStatus.freeze(wallet.id(), "  "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("audit reason");
        assertThatThrownBy(() -> env.changeStatus.unfreeze(wallet.id(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThat(env.wallets.findById(wallet.id()).orElseThrow().isActive()).isTrue();
    }

    @Test
    void unknownWalletIsNotFound() {
        assertThatThrownBy(() -> env.changeStatus.freeze("wal_0123456789abcdef0123456789abcdef",
                "case"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("not found");
        assertThatThrownBy(() -> env.changeStatus.unfreeze("  ", "x"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wallet id");
    }
}
