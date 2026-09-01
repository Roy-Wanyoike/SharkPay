package com.sharkpay.wallet.api;

import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.testsupport.WalletTestEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Public read side (contracts/openapi/v1/wallets.yaml — read-only contract):
 * listWallets, getWallet with balance partitions, getWalletStatement.
 * Standalone MockMvc + Jackson 3.
 */
class WalletControllerTest {

    private WalletTestEnv env;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        env = new WalletTestEnv();
        mockMvc = env.mockMvc();
    }

    @Test
    void listsWalletsWithBalancesAndCursorPagination() throws Exception {
        Wallet first = env.newWallet("KES");
        env.credit(first, 50_000);
        for (int i = 0; i < 3; i++) {
            env.newWallet("KES");
        }

        mockMvc.perform(get("/wallets").param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].id").isNotEmpty())
                .andExpect(jsonPath("$.next_cursor").isNotEmpty());

        // follow the cursor to the remaining page
        String cursor = env.listWallets.list(null, null, null, 2, null).nextCursor();
        mockMvc.perform(get("/wallets").param("limit", "2").param("cursor", cursor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)));

        // all wallets on one big page: no next_cursor
        mockMvc.perform(get("/wallets").param("limit", "100"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(4)))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void listWalletsFiltersByPrincipalCurrencyAndStatus() throws Exception {
        UUID principal = env.newPrincipal();
        Wallet kes = env.createWallet.create("k1", principal, "KES").wallet();
        Wallet usd = env.createWallet.create("k2", principal, "USD").wallet();
        env.changeStatus.freeze(kes.id(), "case-1");

        mockMvc.perform(get("/wallets").param("principal_id", principal.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)));
        mockMvc.perform(get("/wallets").param("principal_id", principal.toString())
                        .param("currency", "kes"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(kes.id()));
        mockMvc.perform(get("/wallets").param("status", "frozen"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(kes.id()));
        mockMvc.perform(get("/wallets").param("status", "active"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(usd.id()));
    }

    @Test
    void listWalletsRejectsBadLimitsAndStatusValues() throws Exception {
        mockMvc.perform(get("/wallets").param("limit", "0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
        mockMvc.perform(get("/wallets").param("status", "bogus"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
    }

    @Test
    void readsAWalletWithItsBalancePartitions() throws Exception {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 125_000);
        env.placeHold.place("k1", wallet.id(), 50_000, "KES",
                com.sharkpay.wallet.domain.Source.PAYMENTS, UUID.randomUUID(), null);

        mockMvc.perform(get("/wallets/{id}", wallet.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(wallet.id()))
                .andExpect(jsonPath("$.principal_id").value(wallet.principalId().toString()))
                .andExpect(jsonPath("$.currency").value("KES"))
                .andExpect(jsonPath("$.status").value("active"))
                .andExpect(jsonPath("$.balances.available.amount_minor").value(75_000))
                .andExpect(jsonPath("$.balances.available.exponent").value(2))
                .andExpect(jsonPath("$.balances.pending.amount_minor").value(0))
                .andExpect(jsonPath("$.balances.held.amount_minor").value(50_000))
                .andExpect(jsonPath("$.created_at", startsWith("2026-09-01T10:00:00")))
                .andExpect(jsonPath("$.closed_at").doesNotExist());
    }

    @Test
    void unknownWalletIsA404() throws Exception {
        mockMvc.perform(get("/wallets/{id}", "wal_0123456789abcdef0123456789abcdef"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));
    }

    @Test
    void readsTheStatementInLedgerOrderWithCursors() throws Exception {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 100);   // posting 10001, balance_after 100
        env.debit(wallet, 30);     // posting 10003, balance_after 70
        env.credit(wallet, 10);    // posting 10005, balance_after 80

        mockMvc.perform(get("/wallets/{id}/statement", wallet.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(3)))
                .andExpect(jsonPath("$.items[0].id").value("10001"))
                .andExpect(jsonPath("$.items[0].entry_type").value("capture"))
                .andExpect(jsonPath("$.items[0].direction").value("credit"))
                .andExpect(jsonPath("$.items[0].amount.amount_minor").value(100))
                .andExpect(jsonPath("$.items[0].balance_after.amount_minor").value(100))
                .andExpect(jsonPath("$.items[1].id").value("10003"))
                .andExpect(jsonPath("$.items[1].direction").value("debit"))
                .andExpect(jsonPath("$.items[1].balance_after.amount_minor").value(70))
                .andExpect(jsonPath("$.items[2].id").value("10005"))
                .andExpect(jsonPath("$.items[2].balance_after.amount_minor").value(80))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());

        // paginated
        mockMvc.perform(get("/wallets/{id}/statement", wallet.id()).param("limit", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.next_cursor").value("10003"));
        mockMvc.perform(get("/wallets/{id}/statement", wallet.id())
                        .param("limit", "2").param("cursor", "10003"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value("10005"))
                .andExpect(jsonPath("$.next_cursor").doesNotExist());
    }

    @Test
    void aFreshWalletHasAnEmptyStatementAndErrorsAreTyped() throws Exception {
        Wallet wallet = env.newWallet("KES");

        mockMvc.perform(get("/wallets/{id}/statement", wallet.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));

        mockMvc.perform(get("/wallets/{id}/statement", "wal_0123456789abcdef0123456789abcdef"))
                .andExpect(status().isNotFound());
        mockMvc.perform(get("/wallets/{id}/statement", wallet.id()).param("cursor", "abc"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));
        mockMvc.perform(get("/wallets/{id}/statement", wallet.id()).param("limit", "-1"))
                .andExpect(status().isBadRequest());
    }
}
