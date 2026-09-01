package com.sharkpay.wallet.api;

import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.testsupport.WalletTestEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Internal wallet adapter (standalone MockMvc, Jackson 3, no Spring context):
 * wallet creation + freeze/unfreeze + place-hold, all money-mutating
 * operations idempotent by Idempotency-Key (same key ⇒ same response body,
 * no second effect; different payload ⇒ 409).
 */
class InternalWalletControllerTest {

    private WalletTestEnv env;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        env = new WalletTestEnv();
        mockMvc = env.mockMvc();
    }

    @Nested
    class CreateWallet {

        @Test
        void createsAnActiveWalletWithZeroBalances() throws Exception {
            UUID principal = env.newPrincipal();

            mockMvc.perform(post("/internal/wallets")
                            .header("Idempotency-Key", "key-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"principal_id\":\"" + principal + "\",\"currency\":\"kes\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(matchesPattern("^wal_[0-9a-f]{32}$")))
                    .andExpect(jsonPath("$.principal_id").value(principal.toString()))
                    .andExpect(jsonPath("$.currency").value("KES"))
                    .andExpect(jsonPath("$.status").value("active"))
                    .andExpect(jsonPath("$.balances.available.amount_minor").value(0))
                    .andExpect(jsonPath("$.balances.available.currency").value("KES"))
                    .andExpect(jsonPath("$.balances.available.exponent").value(2))
                    .andExpect(jsonPath("$.balances.pending.amount_minor").value(0))
                    .andExpect(jsonPath("$.balances.held.amount_minor").value(0))
                    .andExpect(jsonPath("$.created_at", startsWith("2026-09-01T10:00:00")))
                    .andExpect(jsonPath("$.closed_at").doesNotExist());

            assertThat(env.wallets.count()).isEqualTo(1);
        }

        @Test
        void sameKeySamePayloadReplaysTheSameResponseWithNoSecondEffect() throws Exception {
            UUID principal = env.newPrincipal();

            MvcResult first = mockMvc.perform(post("/internal/wallets")
                            .header("Idempotency-Key", "key-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"principal_id\":\"" + principal + "\",\"currency\":\"KES\"}"))
                    .andExpect(status().isCreated())
                    .andReturn();

            MvcResult replay = mockMvc.perform(post("/internal/wallets")
                            .header("Idempotency-Key", "key-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"principal_id\":\"" + principal + "\",\"currency\":\"KES\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(headerValue("X-Idempotent-Replay", "true"))
                    .andReturn();

            assertThat(replay.getResponse().getContentAsString())
                    .isEqualTo(first.getResponse().getContentAsString());
            assertThat(env.wallets.count()).isEqualTo(1);
        }

        @Test
        void sameKeyDifferentPayloadIsA409Conflict() throws Exception {
            UUID principal = env.newPrincipal();
            UUID other = env.newPrincipal();
            create(principal, "KES", "key-1");

            mockMvc.perform(post("/internal/wallets")
                            .header("Idempotency-Key", "key-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"principal_id\":\"" + other + "\",\"currency\":\"KES\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("idempotency_conflict"))
                    .andExpect(jsonPath("$.error.message").value(
                            org.hamcrest.Matchers.containsString("key-1")))
                    .andExpect(jsonPath("$.error.request_id").isNotEmpty());

            assertThat(env.wallets.count()).isEqualTo(1);
        }

        @Test
        void missingIdempotencyKeyIsA400() throws Exception {
            UUID principal = env.newPrincipal();

            mockMvc.perform(post("/internal/wallets")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"principal_id\":\"" + principal + "\",\"currency\":\"KES\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("validation_error"))
                    .andExpect(jsonPath("$.error.message").value(
                            org.hamcrest.Matchers.containsString("Idempotency-Key")));

            assertThat(env.wallets.count()).isZero();
        }

        @Test
        void duplicateWalletForTheSamePrincipalAndCurrencyIsA409() throws Exception {
            UUID principal = env.newPrincipal();
            create(principal, "KES", "key-1");

            mockMvc.perform(post("/internal/wallets")
                            .header("Idempotency-Key", "key-2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"principal_id\":\"" + principal + "\",\"currency\":\"KES\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("duplicate_wallet"));
        }

        @Test
        void unknownPrincipalIsA404AndUnsupportedCurrencyA400() throws Exception {
            mockMvc.perform(post("/internal/wallets")
                            .header("Idempotency-Key", "key-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"principal_id\":\"" + UUID.randomUUID()
                                    + "\",\"currency\":\"KES\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("not_found"));

            UUID principal = env.newPrincipal();
            mockMvc.perform(post("/internal/wallets")
                            .header("Idempotency-Key", "key-2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"principal_id\":\"" + principal + "\",\"currency\":\"XYZ\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("validation_error"));
        }

        @Test
        void blankBodyFieldsAreA400() throws Exception {
            mockMvc.perform(post("/internal/wallets")
                            .header("Idempotency-Key", "key-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("validation_error"));
        }

        private void create(UUID principal, String currency, String key) throws Exception {
            mockMvc.perform(post("/internal/wallets")
                    .header("Idempotency-Key", key)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"principal_id\":\"" + principal + "\",\"currency\":\"" + currency + "\"}"))
                    .andExpect(status().isCreated());
        }
    }

    @Nested
    class FreezeUnfreeze {

        @Test
        void freezesWithReasonAndThenUnfreezes() throws Exception {
            Wallet wallet = fundedWallet();

            mockMvc.perform(post("/internal/wallets/{id}/freeze", wallet.id())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"compliance case-77\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("frozen"));

            mockMvc.perform(post("/internal/wallets/{id}/unfreeze", wallet.id())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"case-77 cleared\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("active"));
        }

        @Test
        void doubleFreezeIsA409StateConflict() throws Exception {
            Wallet wallet = fundedWallet();

            mockMvc.perform(post("/internal/wallets/{id}/freeze", wallet.id())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"case-1\"}"))
                    .andExpect(status().isOk());
            mockMvc.perform(post("/internal/wallets/{id}/freeze", wallet.id())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"case-1 again\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("state_conflict"));
        }

        @Test
        void missingOrBlankReasonIsA400() throws Exception {
            Wallet wallet = fundedWallet();

            mockMvc.perform(post("/internal/wallets/{id}/freeze", wallet.id())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("validation_error"))
                    .andExpect(jsonPath("$.error.message").value(
                            org.hamcrest.Matchers.containsString("reason")));
            mockMvc.perform(post("/internal/wallets/{id}/freeze", wallet.id())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"   \"}"))
                    .andExpect(status().isBadRequest());
        }

        @Test
        void unknownWalletIsA404() throws Exception {
            mockMvc.perform(post("/internal/wallets/{id}/freeze", "wal_0123456789abcdef0123456789abcdef")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"x\"}"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("not_found"));
        }
    }

    @Nested
    class PlaceHold {

        @Test
        void placesAHoldReducingAvailableOnly() throws Exception {
            Wallet wallet = fundedWallet();
            UUID sourceRef = UUID.randomUUID();

            mockMvc.perform(post("/internal/wallets/{id}/holds", wallet.id())
                            .header("Idempotency-Key", "hold-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount_minor\":40000,\"currency\":\"KES\","
                                    + "\"source\":\"payments\",\"source_ref\":\"" + sourceRef + "\"}"))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.id").value(matchesPattern("^hld_[0-9a-f]{32}$")))
                    .andExpect(jsonPath("$.wallet_id").value(wallet.id()))
                    .andExpect(jsonPath("$.state").value("active"))
                    .andExpect(jsonPath("$.amount.amount_minor").value(40000))
                    .andExpect(jsonPath("$.captured_amount.amount_minor").value(0))
                    .andExpect(jsonPath("$.released_amount.amount_minor").value(0))
                    .andExpect(jsonPath("$.source").value("payments"))
                    .andExpect(jsonPath("$.source_ref").value(sourceRef.toString()))
                    .andExpect(jsonPath("$.created_at", startsWith("2026-09-01T")));

            assertThat(env.balanceReader.balancesOf(wallet).available().amountMinor())
                    .isEqualTo(60_000);
            assertThat(env.balanceReader.balancesOf(wallet).total().amountMinor())
                    .isEqualTo(100_000);
        }

        @Test
        void sameKeyReplaysTheSameHoldResponseWithNoDoubleReservation() throws Exception {
            Wallet wallet = fundedWallet();
            UUID sourceRef = UUID.randomUUID();
            String body = "{\"amount_minor\":40000,\"currency\":\"KES\","
                    + "\"source\":\"payments\",\"source_ref\":\"" + sourceRef + "\"}";

            MvcResult first = mockMvc.perform(post("/internal/wallets/{id}/holds", wallet.id())
                            .header("Idempotency-Key", "hold-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andReturn();

            MvcResult replay = mockMvc.perform(post("/internal/wallets/{id}/holds", wallet.id())
                            .header("Idempotency-Key", "hold-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isCreated())
                    .andExpect(headerValue("X-Idempotent-Replay", "true"))
                    .andReturn();

            assertThat(replay.getResponse().getContentAsString())
                    .isEqualTo(first.getResponse().getContentAsString());

            // no double reservation: available reflects ONE hold of 40_000
            assertThat(env.balanceReader.balancesOf(wallet).held().amountMinor()).isEqualTo(40_000);
            assertThat(env.balanceReader.balancesOf(wallet).available().amountMinor())
                    .isEqualTo(60_000);
            assertThat(env.holds.count()).isEqualTo(1);
        }

        @Test
        void sameKeyDifferentPayloadIsA409() throws Exception {
            Wallet wallet = fundedWallet();
            UUID sourceRef = UUID.randomUUID();

            mockMvc.perform(post("/internal/wallets/{id}/holds", wallet.id())
                            .header("Idempotency-Key", "hold-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount_minor\":40000,\"currency\":\"KES\","
                                    + "\"source\":\"payments\",\"source_ref\":\"" + sourceRef + "\"}"))
                    .andExpect(status().isCreated());

            mockMvc.perform(post("/internal/wallets/{id}/holds", wallet.id())
                            .header("Idempotency-Key", "hold-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount_minor\":50000,\"currency\":\"KES\","
                                    + "\"source\":\"payments\",\"source_ref\":\"" + sourceRef + "\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("idempotency_conflict"));
            assertThat(env.holds.count()).isEqualTo(1);
        }

        @Test
        void insufficientFundsIsA422WithDetails() throws Exception {
            Wallet wallet = fundedWallet();

            mockMvc.perform(post("/internal/wallets/{id}/holds", wallet.id())
                            .header("Idempotency-Key", "hold-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount_minor\":100001,\"currency\":\"KES\","
                                    + "\"source\":\"payments\",\"source_ref\":\""
                                    + UUID.randomUUID() + "\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("insufficient_funds"))
                    .andExpect(jsonPath("$.error.details.available_minor").value(100_000))
                    .andExpect(jsonPath("$.error.details.requested_minor").value(100_001))
                    .andExpect(jsonPath("$.error.details.currency").value("KES"));

            assertThat(env.holds.count()).isZero();
        }

        @Test
        void currencyMismatchAgainstTheWalletIsA422() throws Exception {
            Wallet wallet = fundedWallet();

            mockMvc.perform(post("/internal/wallets/{id}/holds", wallet.id())
                            .header("Idempotency-Key", "hold-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount_minor\":100,\"currency\":\"USD\","
                                    + "\"source\":\"payments\",\"source_ref\":\""
                                    + UUID.randomUUID() + "\"}"))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.error.code").value("currency_mismatch"))
                    .andExpect(jsonPath("$.error.message").value(
                            org.hamcrest.Matchers.containsString("KES")));

            assertThat(env.holds.count()).isZero();
        }

        @Test
        void frozenWalletsRejectNewHoldsAndUnknownWallets404() throws Exception {
            Wallet wallet = fundedWallet();
            env.changeStatus.freeze(wallet.id(), "case-1");

            mockMvc.perform(post("/internal/wallets/{id}/holds", wallet.id())
                            .header("Idempotency-Key", "hold-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount_minor\":1,\"currency\":\"KES\","
                                    + "\"source\":\"payments\",\"source_ref\":\""
                                    + UUID.randomUUID() + "\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("state_conflict"))
                    .andExpect(jsonPath("$.error.message").value(
                            org.hamcrest.Matchers.containsString("frozen")));

            mockMvc.perform(post("/internal/wallets/{id}/holds", "wal_0123456789abcdef0123456789abcdef")
                            .header("Idempotency-Key", "hold-2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount_minor\":1,\"currency\":\"KES\","
                                    + "\"source\":\"payments\",\"source_ref\":\""
                                    + UUID.randomUUID() + "\"}"))
                    .andExpect(status().isNotFound());
        }

        @Test
        void nonPositiveAmountsAreA400() throws Exception {
            Wallet wallet = fundedWallet();
            for (long amount : new long[]{0, -5}) {
                mockMvc.perform(post("/internal/wallets/{id}/holds", wallet.id())
                                .header("Idempotency-Key", "hold-" + amount)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount_minor\":" + amount + ",\"currency\":\"KES\","
                                        + "\"source\":\"payments\",\"source_ref\":\""
                                        + UUID.randomUUID() + "\"}"))
                        .andExpect(status().isBadRequest());
            }
            assertThat(env.holds.count()).isZero();
        }
    }

    private Wallet fundedWallet() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 100_000);
        return wallet;
    }

    private static org.springframework.test.web.servlet.ResultMatcher headerValue(
            String name, String value) {
        return result -> {
            String actual = result.getResponse().getHeader(name);
            if (!value.equals(actual)) {
                throw new AssertionError("expect header " + name + "=" + value + " but was " + actual);
            }
        };
    }
}
