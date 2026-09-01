package com.sharkpay.wallet.service;

import com.sharkpay.money.Money;
import com.sharkpay.wallet.domain.Source;
import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.domain.WalletStatus;
import com.sharkpay.wallet.testsupport.WalletTestEnv;
import org.junit.jupiter.api.Test;

import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Read-side use-cases: get wallet, list wallets, wallet statement. */
class ReadSideUseCasesTest {

    private final WalletTestEnv env = new WalletTestEnv();

    @Test
    void getWalletReturnsThePartitions() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 100_000);
        env.placeHold.place("k1", wallet.id(), 30_000, "KES", Source.PAYMENTS,
                UUID.randomUUID(), null);

        GetWalletUseCase.WalletWithBalances result = env.getWallet.get(wallet.id());

        assertThat(result.wallet()).isEqualTo(wallet);
        assertThat(result.balances().total()).isEqualTo(Money.of(100_000, "KES"));
        assertThat(result.balances().held()).isEqualTo(Money.of(30_000, "KES"));
        assertThat(result.balances().pending()).isEqualTo(Money.zero("KES"));
        assertThat(result.balances().available()).isEqualTo(Money.of(70_000, "KES"));
    }

    @Test
    void getWalletOfAFreshWalletIsAllZero() {
        Wallet wallet = env.newWallet("USDC");

        GetWalletUseCase.WalletWithBalances result = env.getWallet.get(wallet.id());

        assertThat(result.balances().total()).isEqualTo(Money.zero("USDC"));
        assertThat(result.balances().available()).isEqualTo(Money.zero("USDC"));
    }

    @Test
    void getWalletTrimsAndValidatesTheId() {
        Wallet wallet = env.newWallet("KES");
        assertThat(env.getWallet.get(" " + wallet.id() + " ").wallet()).isEqualTo(wallet);
        assertThatThrownBy(() -> env.getWallet.get("wal_short"))
                .isInstanceOf(NoSuchElementException.class);
        assertThatThrownBy(() -> env.getWallet.get(" "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("wallet id");
    }

    @Test
    void listWalletsFiltersByPrincipalCurrencyAndStatus() {
        UUID principal = env.newPrincipal();
        Wallet kes = env.createWallet.create("k1", principal, "KES").wallet();
        Wallet usd = env.createWallet.create("k2", principal, "USD").wallet();
        Wallet otherPrincipalKes = env.newWallet("KES");
        env.changeStatus.freeze(kes.id(), "case-1");

        assertThat(env.listWallets.list(principal, null, null, null, null).items())
                .extracting(item -> item.wallet().id())
                .containsExactlyInAnyOrder(kes.id(), usd.id());
        assertThat(env.listWallets.list(principal, "KES", null, null, null).items())
                .extracting(item -> item.wallet().id())
                .containsExactly(kes.id());
        assertThat(env.listWallets.list(principal, "kes", null, null, null).items())
                .extracting(item -> item.wallet().id())
                .containsExactly(kes.id());   // currency filter is case-insensitive
        assertThat(env.listWallets.list(null, null, WalletStatus.FROZEN, null, null).items())
                .extracting(item -> item.wallet().id())
                .containsExactly(kes.id());
        assertThat(env.listWallets.list(null, null, WalletStatus.ACTIVE, null, null).items())
                .extracting(item -> item.wallet().id())
                .containsExactlyInAnyOrder(usd.id(), otherPrincipalKes.id());
    }

    @Test
    void listWalletsPaginatesByIdCursor() {
        for (int i = 0; i < 5; i++) {
            env.newWallet("KES");
        }

        ListWalletsUseCase.Result page1 = env.listWallets.list(null, null, null, 2, null);
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.nextCursor()).isNotBlank();

        ListWalletsUseCase.Result page2 = env.listWallets.list(null, null, null, 2,
                page1.nextCursor());
        assertThat(page2.items()).hasSize(2);
        assertThat(page2.items().get(0).wallet().id()).isGreaterThan(page1.items().get(1)
                .wallet().id());

        // walk to the end: last page has no cursor
        String cursor = page2.nextCursor();
        ListWalletsUseCase.Result last = env.listWallets.list(null, null, null, 100, cursor);
        assertThat(last.nextCursor()).isNull();
        assertThat(page1.items().size() + page2.items().size() + last.items().size()).isEqualTo(5);
    }

    @Test
    void limitIsNormalizedWithDefaultsAndBounds() {
        assertThat(ListWalletsUseCase.normalizeLimit(null)).isEqualTo(20);
        assertThat(ListWalletsUseCase.normalizeLimit(5)).isEqualTo(5);
        assertThat(ListWalletsUseCase.normalizeLimit(10_000)).isEqualTo(100);
        assertThatThrownBy(() -> ListWalletsUseCase.normalizeLimit(0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
        assertThatThrownBy(() -> ListWalletsUseCase.normalizeLimit(-3))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void theStatementIsCursorPaginatedInPostingOrder() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 100);      // posting 10001
        env.debit(wallet, 30);        // posting 10003
        env.credit(wallet, 10);       // posting 10005

        GetStatementUseCase.Result page1 = env.statement.statement(wallet.id(), 2, null);
        assertThat(page1.items()).hasSize(2);
        assertThat(page1.nextCursor()).isEqualTo("10003");

        GetStatementUseCase.Result page2 = env.statement.statement(wallet.id(), 2,
                page1.nextCursor());
        assertThat(page2.items()).hasSize(1);
        assertThat(page2.items().get(0).leg().postingId()).isEqualTo(10005L);
        assertThat(page2.items().get(0).balanceAfter()).isEqualTo(Money.of(80, "KES"));
        assertThat(page2.nextCursor()).isNull();

        // balance_after chain matches the running ledger order
        assertThat(env.statement.statement(wallet.id(), 10, null).items())
                .extracting(line -> line.balanceAfter().amountMinor())
                .containsExactly(100L, 70L, 80L);
    }

    @Test
    void statementErrorsAreTyped() {
        Wallet wallet = env.newWallet("KES");
        assertThatThrownBy(() -> env.statement.statement("wal_0123456789abcdef0123456789abcdee",
                null, null))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("not found");
        assertThatThrownBy(() -> env.statement.statement(wallet.id(), null, "not-a-number"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("malformed statement cursor");
        assertThatThrownBy(() -> env.statement.statement(wallet.id(), null, "0"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(">= 1");
        assertThatThrownBy(() -> env.statement.statement(wallet.id(), 0, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void aFreshWalletHasAnEmptyStatement() {
        Wallet wallet = env.newWallet("KES");
        assertThat(env.statement.statement(wallet.id(), null, null).items()).isEmpty();
    }
}
