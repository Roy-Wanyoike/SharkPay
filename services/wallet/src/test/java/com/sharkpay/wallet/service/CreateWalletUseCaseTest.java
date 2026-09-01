package com.sharkpay.wallet.service;

import com.sharkpay.money.UnknownCurrencyException;
import com.sharkpay.wallet.domain.DuplicateWalletException;
import com.sharkpay.wallet.domain.IdempotencyConflictException;
import com.sharkpay.wallet.domain.PrincipalNotActiveException;
import com.sharkpay.wallet.domain.UnknownPrincipalException;
import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.events.CloudEvent;
import com.sharkpay.wallet.events.WalletEvents;
import com.sharkpay.wallet.ports.PrincipalLookup.PrincipalStatus;
import com.sharkpay.wallet.testsupport.WalletTestEnv;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CreateWalletUseCaseTest {

    private final WalletTestEnv env = new WalletTestEnv();

    @Test
    void createsAnActiveWalletAndProvisionsItsLedgerAccount() {
        UUID principal = env.newPrincipal();

        CreateWalletUseCase.Result result = env.createWallet.create("key-1", principal, "kes");

        assertThat(result.replay()).isFalse();
        Wallet wallet = result.wallet();
        assertThat(wallet.id()).matches("^wal_[0-9a-f]{32}$");
        assertThat(wallet.currency()).isEqualTo("KES");
        assertThat(wallet.status().wireName()).isEqualTo("active");
        assertThat(wallet.principalId()).isEqualTo(principal);
        assertThat(wallet.ledgerAccountId())
                .isEqualTo(env.ledgerAccounts.accountId(principal, "KES"));
        assertThat(env.wallets.count()).isEqualTo(1);
        assertThat(env.ledgerAccounts.distinctAccounts()).isEqualTo(1);
    }

    @Test
    void creationPublishesWalletStateChangedWithOmittedFromStatus() {
        UUID principal = env.newPrincipal();
        env.createWallet.create("key-1", principal, "KES");

        assertThat(env.events.events()).hasSize(1);
        CloudEvent event = env.events.last();
        assertThat(event.type()).isEqualTo(WalletEvents.STATE_CHANGED);
        assertThat(event.subject()).matches("^wal_[0-9a-f]{32}$");
        WalletEvents.WalletStateData data = (WalletEvents.WalletStateData) event.data();
        assertThat(data.from_status()).isNull();   // omitted on creation
        assertThat(data.to_status()).isEqualTo("active");
        assertThat(data.reason()).isEqualTo("wallet created");
        assertThat(data.principal_id()).isEqualTo(principal);
    }

    @Test
    void sameKeySamePayloadReplaysTheOriginalWalletWithNoSecondEffect() {
        UUID principal = env.newPrincipal();
        CreateWalletUseCase.Result first = env.createWallet.create("key-1", principal, "KES");
        env.events.reset();

        CreateWalletUseCase.Result replay = env.createWallet.create("key-1", principal, "KES");

        assertThat(replay.replay()).isTrue();
        assertThat(replay.wallet()).isEqualTo(first.wallet());
        assertThat(env.wallets.count()).isEqualTo(1);
        // no second provisioning call either — replay short-circuits everything
        assertThat(env.ledgerAccounts.provisioningLog()).hasSize(1);
        assertThat(env.ledgerAccounts.distinctAccounts()).isEqualTo(1);
        assertThat(env.events.events()).isEmpty();   // no second event
    }

    @Test
    void sameKeyDifferentPayloadIsAConflictWithNoEffect() {
        UUID principal = env.newPrincipal();
        UUID other = env.newPrincipal();
        env.createWallet.create("key-1", principal, "KES");

        assertThatThrownBy(() -> env.createWallet.create("key-1", other, "KES"))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThatThrownBy(() -> env.createWallet.create("key-1", principal, "USD"))
                .isInstanceOf(IdempotencyConflictException.class);
        assertThat(env.wallets.count()).isEqualTo(1);
    }

    @Test
    void aPrincipalHasAtMostOneWalletPerCurrency() {
        UUID principal = env.newPrincipal();
        env.createWallet.create("key-1", principal, "KES");

        assertThatThrownBy(() -> env.createWallet.create("key-2", principal, "KES"))
                .isInstanceOf(DuplicateWalletException.class)
                .hasMessageContaining("already has a KES wallet");
    }

    @Test
    void theSamePrincipalCanHoldWalletsInDifferentCurrencies() {
        UUID principal = env.newPrincipal();
        env.createWallet.create("key-1", principal, "KES");
        env.createWallet.create("key-2", principal, "USD");
        env.createWallet.create("key-3", principal, "USDC");

        assertThat(env.wallets.count()).isEqualTo(3);
        assertThat(env.ledgerAccounts.distinctAccounts()).isEqualTo(3);
    }

    @Test
    void everySupportedCurrencyIsAccepted() {
        for (String currency : new String[]{"KES", "USD", "EUR", "GBP", "USDC", "USDT"}) {
            UUID principal = env.newPrincipal();
            assertThat(env.createWallet.create("key-" + currency, principal, currency)
                    .wallet().currency()).isEqualTo(currency);
        }
        assertThat(env.wallets.count()).isEqualTo(6);
    }

    @Test
    void unknownPrincipalsAreRejected() {
        assertThatThrownBy(() -> env.createWallet.create("key-1", UUID.randomUUID(), "KES"))
                .isInstanceOf(UnknownPrincipalException.class)
                .hasMessageContaining("not found");
        assertThat(env.wallets.count()).isZero();
    }

    @Test
    void nonActivePrincipalsAreRejected() {
        UUID suspended = UUID.randomUUID();
        env.principals.register(suspended, PrincipalStatus.SUSPENDED);
        UUID closed = UUID.randomUUID();
        env.principals.register(closed, PrincipalStatus.CLOSED);

        assertThatThrownBy(() -> env.createWallet.create("key-1", suspended, "KES"))
                .isInstanceOf(PrincipalNotActiveException.class)
                .hasMessageContaining("SUSPENDED");
        assertThatThrownBy(() -> env.createWallet.create("key-2", closed, "KES"))
                .isInstanceOf(PrincipalNotActiveException.class);
        assertThat(env.wallets.count()).isZero();
    }

    @Test
    void unsupportedCurrenciesAreRejected() {
        UUID principal = env.newPrincipal();
        assertThatThrownBy(() -> env.createWallet.create("key-1", principal, "XYZ"))
                .isInstanceOf(UnknownCurrencyException.class);
        assertThat(env.wallets.count()).isZero();
    }

    @Test
    void blankIdempotencyKeysAreRejected() {
        UUID principal = env.newPrincipal();
        assertThatThrownBy(() -> env.createWallet.create("   ", principal, "KES"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> env.createWallet.create(null, principal, "KES"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aMissingReferencedWalletOnReplayIsAnError() {
        UUID principal = env.newPrincipal();
        String fingerprint = CreateWalletUseCase.fingerprint(principal, "KES");
        env.idempotency.put(com.sharkpay.wallet.ports.IdempotencyStore.Scope.CREATE_WALLET,
                "ghost-key", new com.sharkpay.wallet.ports.IdempotencyStore.StoredRequest(
                        fingerprint, "wal_0123456789abcdef0123456789abcdef"));

        assertThatThrownBy(() -> env.createWallet.create("ghost-key", principal, "KES"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("referenced by idempotency key");
        assertThat(env.wallets.count()).isZero();
    }
}
