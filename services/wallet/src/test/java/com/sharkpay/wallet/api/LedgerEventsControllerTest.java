package com.sharkpay.wallet.api;

import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.fakes.FakeLedgerFeed;
import com.sharkpay.wallet.testsupport.WalletTestEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Dev ledger-feed ingestion endpoint (standalone MockMvc + Jackson 3):
 * {@code POST /internal/ledger-events} binds the
 * {@code ledger.posting.committed.v1} CloudEvent envelope (contracts/events/
 * ledger.posting.v1.json) and feeds the balance projection — idempotent and
 * duplicate-safe.
 */
class LedgerEventsControllerTest {

    private WalletTestEnv env;
    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        env = new WalletTestEnv();
        mockMvc = env.mockMvc();
    }

    @Test
    void ingestsACommittedEventAndProjectsTheTotal() throws Exception {
        Wallet wallet = env.newWallet("KES");

        mockMvc.perform(post("/internal/ledger-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(env.accountOf(wallet), "KES", 0, 150_000, 10_241)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.event_id").isNotEmpty())
                .andExpect(jsonPath("$.legs_applied").value(2));

        assertThat(env.balanceReader.balancesOf(wallet).total().amountMinor())
                .isEqualTo(150_000);
    }

    @Test
    void duplicateDeliveryIsANoOpWithZeroLegs() throws Exception {
        Wallet wallet = env.newWallet("KES");
        String body = eventJson(env.accountOf(wallet), "KES", 0, 150_000, 10_241);

        mockMvc.perform(post("/internal/ledger-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.legs_applied").value(2));

        // exact redelivery: accepted, nothing applied, total unchanged
        mockMvc.perform(post("/internal/ledger-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.legs_applied").value(0));

        assertThat(env.balanceReader.balancesOf(wallet).total().amountMinor())
                .isEqualTo(150_000);
    }

    @Test
    void debitEventsReduceTheTotal() throws Exception {
        Wallet wallet = env.newWallet("KES");
        mockMvc.perform(post("/internal/ledger-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(env.accountOf(wallet), "KES", 0, 150_000, 10_241)))
                .andExpect(status().isAccepted());

        mockMvc.perform(post("/internal/ledger-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(env.accountOf(wallet), "KES", 50_000, 0, 10_251)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.legs_applied").value(2));

        assertThat(env.balanceReader.balancesOf(wallet).total().amountMinor())
                .isEqualTo(100_000);
        assertThat(env.balanceReader.balancesOf(wallet).available().amountMinor())
                .isEqualTo(100_000);
    }

    @Test
    void legsOfUnknownAccountsAreIgnored() throws Exception {
        Wallet wallet = env.newWallet("KES");

        mockMvc.perform(post("/internal/ledger-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(FakeLedgerFeed.CLEARING_ACCOUNT, "KES", 0, 150_000,
                                10_241)))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.legs_applied").value(2));

        assertThat(env.balanceReader.balancesOf(wallet).total().amountMinor()).isZero();
        assertThat(env.projections.projectedWalletCount()).isZero();
    }

    @Test
    void malformedEnvelopesAreA400() throws Exception {
        Wallet wallet = env.newWallet("KES");
        // wrong CloudEvents source
        mockMvc.perform(post("/internal/ledger-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson(env.accountOf(wallet), "KES", 0, 150, 10_241)
                                .replace("sharkpay/ledger", "sharkpay/wallet")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));

        // a single-leg entry violates the schema (minItems 2)
        String singleLeg = """
                {"id":"%s","type":"ledger.posting.committed.v1","specversion":"1.0",
                 "source":"sharkpay/ledger","subject":"%s","occurred_at":"2026-09-01T10:00:02Z",
                 "data":{"entry_id":"%s","transaction_key":"payments:xyz:capture",
                         "source":"payments","source_ref":"%s","entry_type":"capture",
                         "postings":[{"posting_id":10241,"account_id":"%s",
                                      "currency":"KES","debit":0,"credit":150}]}}
                """.formatted(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                env.accountOf(wallet));
        mockMvc.perform(post("/internal/ledger-events")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(singleLeg))
                .andExpect(status().isBadRequest());
    }

    /** A balanced two-leg entry: wallet leg + provider clearing counter-leg. */
    private static String eventJson(java.util.UUID walletAccount, String currency, long debit,
                                    long credit, long postingId) {
        java.util.UUID entryId = java.util.UUID.randomUUID();
        java.util.UUID eventId = java.util.UUID.randomUUID();
        java.util.UUID sourceRef = java.util.UUID.randomUUID();
        return """
                {"id":"%s","type":"ledger.posting.committed.v1","specversion":"1.0",
                 "source":"sharkpay/ledger","subject":"%s","occurred_at":"2026-09-01T10:00:02Z",
                 "data":{"entry_id":"%s","transaction_key":"payments:%s:capture",
                         "source":"payments","source_ref":"%s","entry_type":"capture",
                         "postings":[
                           {"posting_id":%d,"account_id":"%s","account_code":"wallet:usr_1:%s",
                            "currency":"%s","debit":%d,"credit":%d},
                           {"posting_id":%d,"account_id":"%s","account_code":"provider:clearing:KES",
                            "currency":"%s","debit":%d,"credit":%d}]}}
                """.formatted(eventId, entryId, entryId, sourceRef, sourceRef,
                postingId, walletAccount, currency, currency, debit, credit,
                postingId + 1, FakeLedgerFeed.CLEARING_ACCOUNT, currency, credit, debit);
    }
}
