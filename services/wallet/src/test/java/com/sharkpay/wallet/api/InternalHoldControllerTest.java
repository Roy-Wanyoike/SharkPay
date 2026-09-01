package com.sharkpay.wallet.api;

import com.sharkpay.wallet.domain.Hold;
import com.sharkpay.wallet.domain.Source;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Internal hold adapter (standalone MockMvc, Jackson 3): read / release /
 * capture funds-control operations — idempotent by Idempotency-Key, partial
 * capture releases the remainder (captured + released = amount).
 */
class InternalHoldControllerTest {

    private WalletTestEnv env;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        env = new WalletTestEnv();
        mockMvc = env.mockMvc();
    }

    @Nested
    class ReadHold {

        @Test
        void readsAHoldWithItsTerminalSplit() throws Exception {
            Hold hold = held(40_000);

            mockMvc.perform(get("/internal/holds/{id}", hold.id()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.id").value(hold.id()))
                    .andExpect(jsonPath("$.wallet_id").value(hold.walletId()))
                    .andExpect(jsonPath("$.state").value("active"))
                    .andExpect(jsonPath("$.amount.amount_minor").value(40_000))
                    .andExpect(jsonPath("$.amount.currency").value("KES"))
                    .andExpect(jsonPath("$.amount.exponent").value(2))
                    .andExpect(jsonPath("$.captured_amount.amount_minor").value(0))
                    .andExpect(jsonPath("$.released_amount.amount_minor").value(0))
                    .andExpect(jsonPath("$.source").value("payments"));
        }

        @Test
        void unknownHoldIsA404() throws Exception {
            mockMvc.perform(get("/internal/holds/{id}", "hld_0123456789abcdef0123456789abcdef"))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.error.code").value("not_found"));
        }
    }

    @Nested
    class Release {

        @Test
        void releasesReturningTheFundsToAvailable() throws Exception {
            Wallet wallet = fundedWallet();
            Hold hold = held(40_000);

            mockMvc.perform(post("/internal/holds/{id}/release", hold.id())
                            .header("Idempotency-Key", "rel-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"not needed\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("released"))
                    .andExpect(jsonPath("$.released_amount.amount_minor").value(40_000))
                    .andExpect(jsonPath("$.captured_amount.amount_minor").value(0));

            assertThat(env.balanceReader.balancesOf(wallet).available().amountMinor())
                    .isEqualTo(100_000);
        }

        @Test
        void sameKeyReplaysTheSameResponseWithNoSecondEffect() throws Exception {
            Hold hold = held(40_000);
            String body = "{\"reason\":\"r\"}";

            MvcResult first = mockMvc.perform(post("/internal/holds/{id}/release", hold.id())
                            .header("Idempotency-Key", "rel-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andReturn();

            MvcResult replay = mockMvc.perform(post("/internal/holds/{id}/release", hold.id())
                            .header("Idempotency-Key", "rel-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(headerValue("X-Idempotent-Replay", "true"))
                    .andReturn();

            assertThat(replay.getResponse().getContentAsString())
                    .isEqualTo(first.getResponse().getContentAsString());
            assertThat(env.holds.count()).isEqualTo(1);
        }

        @Test
        void sameKeyDifferentPayloadIsA409() throws Exception {
            Hold hold = held(40_000);

            mockMvc.perform(post("/internal/holds/{id}/release", hold.id())
                            .header("Idempotency-Key", "rel-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"first\"}"))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/internal/holds/{id}/release", hold.id())
                            .header("Idempotency-Key", "rel-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"reason\":\"different\"}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("idempotency_conflict"));
        }

        @Test
        void releasingAReleasedHoldWithANewKeyIsA409StateConflict() throws Exception {
            Hold hold = held(40_000);
            mockMvc.perform(post("/internal/holds/{id}/release", hold.id())
                            .header("Idempotency-Key", "rel-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/internal/holds/{id}/release", hold.id())
                            .header("Idempotency-Key", "rel-2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("state_conflict"))
                    .andExpect(jsonPath("$.error.message").value(
                            org.hamcrest.Matchers.containsString("released")));
        }

        @Test
        void missingIdempotencyKeyIsA400() throws Exception {
            Hold hold = held(40_000);

            mockMvc.perform(post("/internal/holds/{id}/release", hold.id())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.message").value(
                            org.hamcrest.Matchers.containsString("Idempotency-Key")));
        }

        @Test
        void unknownHoldIsA404() throws Exception {
            mockMvc.perform(post("/internal/holds/{id}/release", "hld_0123456789abcdef0123456789abcdef")
                            .header("Idempotency-Key", "rel-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound());
        }
    }

    @Nested
    class Capture {

        @Test
        void fullCaptureWhenNoAmountIsGiven() throws Exception {
            Hold hold = held(40_000);

            mockMvc.perform(post("/internal/holds/{id}/capture", hold.id())
                            .header("Idempotency-Key", "cap-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("captured"))
                    .andExpect(jsonPath("$.captured_amount.amount_minor").value(40_000))
                    .andExpect(jsonPath("$.released_amount.amount_minor").value(0));
        }

        @Test
        void partialCaptureReleasesTheRemainderImmediately() throws Exception {
            Wallet wallet = fundedWallet();
            Hold hold = held(40_000);

            mockMvc.perform(post("/internal/holds/{id}/capture", hold.id())
                            .header("Idempotency-Key", "cap-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount_minor\":15000,\"reason\":\"partial\"}"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.state").value("captured"))
                    .andExpect(jsonPath("$.amount.amount_minor").value(40_000))
                    .andExpect(jsonPath("$.captured_amount.amount_minor").value(15_000))
                    .andExpect(jsonPath("$.released_amount.amount_minor").value(25_000));

            // captured + released = amount (checked via the response body too)
            Wallet stored = wallet;
            assertThat(env.holds.findById(hold.id()).orElseThrow().captured()
                    .add(env.holds.findById(hold.id()).orElseThrow().released())
                    .amountMinor()).isEqualTo(40_000);
            // the hold is terminal: nothing held, available is total again
            assertThat(env.balanceReader.balancesOf(stored).held().amountMinor()).isZero();
        }

        @Test
        void sameKeyReplaysTheSameCaptureWithNoSecondEffect() throws Exception {
            Hold hold = held(40_000);
            String body = "{\"amount_minor\":15000}";

            MvcResult first = mockMvc.perform(post("/internal/holds/{id}/capture", hold.id())
                            .header("Idempotency-Key", "cap-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andReturn();

            MvcResult replay = mockMvc.perform(post("/internal/holds/{id}/capture", hold.id())
                            .header("Idempotency-Key", "cap-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(body))
                    .andExpect(status().isOk())
                    .andExpect(headerValue("X-Idempotent-Replay", "true"))
                    .andReturn();

            assertThat(replay.getResponse().getContentAsString())
                    .isEqualTo(first.getResponse().getContentAsString());
            assertThat(env.holds.findById(hold.id()).orElseThrow().captured().amountMinor())
                    .isEqualTo(15_000);
        }

        @Test
        void captureAboveTheReservedAmountIsA400AndLeavesTheHoldActive() throws Exception {
            Hold hold = held(40_000);

            mockMvc.perform(post("/internal/holds/{id}/capture", hold.id())
                            .header("Idempotency-Key", "cap-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"amount_minor\":40001}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("validation_error"))
                    .andExpect(jsonPath("$.error.message").value(
                            org.hamcrest.Matchers.containsString("exceeds")));

            assertThat(env.holds.findById(hold.id()).orElseThrow().state().wireName())
                    .isEqualTo("active");
        }

        @Test
        void nonPositiveAmountsAreA400() throws Exception {
            Hold hold = held(40_000);
            for (long amount : new long[]{0, -5}) {
                mockMvc.perform(post("/internal/holds/{id}/capture", hold.id())
                                .header("Idempotency-Key", "cap-" + amount)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"amount_minor\":" + amount + "}"))
                        .andExpect(status().isBadRequest());
            }
            assertThat(env.holds.findById(hold.id()).orElseThrow().state().wireName())
                    .isEqualTo("active");
        }

        @Test
        void capturingACapturedHoldWithANewKeyIsA409() throws Exception {
            Hold hold = held(40_000);
            mockMvc.perform(post("/internal/holds/{id}/capture", hold.id())
                            .header("Idempotency-Key", "cap-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isOk());

            mockMvc.perform(post("/internal/holds/{id}/capture", hold.id())
                            .header("Idempotency-Key", "cap-2")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.error.code").value("state_conflict"))
                    .andExpect(jsonPath("$.error.message").value(
                            org.hamcrest.Matchers.containsString("captured")));
        }

        @Test
        void missingIdempotencyKeyAndUnknownHold() throws Exception {
            Hold hold = held(40_000);

            mockMvc.perform(post("/internal/holds/{id}/capture", hold.id())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.message").value(
                            org.hamcrest.Matchers.containsString("Idempotency-Key")));

            mockMvc.perform(post("/internal/holds/{id}/capture", "hld_0123456789abcdef0123456789abcdef")
                            .header("Idempotency-Key", "cap-1")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{}"))
                    .andExpect(status().isNotFound());
        }
    }

    private Wallet fundedWallet() {
        Wallet wallet = env.newWallet("KES");
        env.credit(wallet, 100_000);
        return wallet;
    }

    private Hold held(long amountMinor) {
        Wallet wallet = fundedWallet();
        return env.placeHold.place("place-" + UUID.randomUUID(), wallet.id(), amountMinor,
                "KES", Source.PAYMENTS, UUID.randomUUID(), null).hold();
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
