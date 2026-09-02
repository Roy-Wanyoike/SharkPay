package com.sharkpay.reconciliation.api;

import com.sharkpay.reconciliation.testsupport.ReconTestEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The 4-eyes execution surface (RB-7 steps 3–5) over standalone MockMvc:
 * operator B approves + the entry executes through the ledger; the
 * requester approving their own draft is a 422 four_eyes_violation; a
 * double-approve is a 409; a ledger business rejection is a 422 with the
 * ledger's code in the details; every rejection posts nothing.
 */
class CompensationsControllerTest {

    private ReconTestEnv env;
    private MockMvc mockMvc;
    private String breakId;
    private String compensationId;

    @BeforeEach
    void setUp() {
        env = new ReconTestEnv();
        mockMvc = env.mockMvc();
        env.seedProviderLine("hc_amount", "CONFIRMED", 150_000, 500);
        env.seedInternalLine("int_amount", "hc_amount", "CONFIRMED", 149_500, 500);
        breakId = env.triggerDefault("key-run").breaks().get(0).id();
        compensationId = env.proposeCompensation.propose("key-prop", breakId, "ops.alice",
                "settlement variance", legs(), null).entry().id();
    }

    @Test
    void operatorBApprovesAndTheEntryExecutesWithTheJournalLink() throws Exception {
        mockMvc.perform(post("/internal/recon/compensations/{id}/approve", compensationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"ops.bob\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(compensationId))
                .andExpect(jsonPath("$.state").value("executed"))
                .andExpect(jsonPath("$.requester").value("ops.alice"))
                .andExpect(jsonPath("$.approver").value("ops.bob"))
                .andExpect(jsonPath("$.ledger_entry_id").isNotEmpty())
                .andExpect(jsonPath("$.executed_at").isNotEmpty())
                .andExpect(jsonPath("$.ledger_replay").value(false))
                .andExpect(jsonPath("$.compensation_key").value("ops:adj:" + breakId));

        // the break is compensated with the audit link
        assertThat(env.breaks.findById(breakId).orElseThrow().compensationId())
                .isEqualTo(compensationId);
        // exactly one posting
        assertThat(env.ledger.committedCount()).isEqualTo(1);
    }

    @Test
    void theRequesterApprovingTheirOwnDraftIsA422FourEyesViolation() throws Exception {
        mockMvc.perform(post("/internal/recon/compensations/{id}/approve", compensationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"ops.alice\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("four_eyes_violation"))
                .andExpect(jsonPath("$.error.message").value(containsString("ops.alice")))
                .andExpect(jsonPath("$.error.message").value(containsString("distinct persons")));

        assertThat(env.ledger.attempts()).isZero();   // nothing moved
        assertThat(env.breaks.findById(breakId).orElseThrow().state().toString())
                .isEqualTo("OPEN");
    }

    @Test
    void aSecondApprovalIsA409StateConflictWithNoSecondPosting() throws Exception {
        approve("ops.bob");

        mockMvc.perform(post("/internal/recon/compensations/{id}/approve", compensationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"ops.carol\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("state_conflict"))
                .andExpect(jsonPath("$.error.message").value(
                        containsString("executes exactly once")));

        assertThat(env.ledger.committedCount()).isEqualTo(1);
        assertThat(env.ledger.attempts()).isEqualTo(1);
    }

    @Test
    void aLedgerBusinessRejectionIsA422WithTheLedgerCodeInTheDetails() throws Exception {
        env.ledger.rejectNext("unbalanced_entry", "entry is off by 500 KES minor units");

        mockMvc.perform(post("/internal/recon/compensations/{id}/approve", compensationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"ops.bob\"}"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("compensation_rejected"))
                .andExpect(jsonPath("$.error.message").value(containsString("unbalanced_entry")))
                .andExpect(jsonPath("$.error.details.ledger_code").value("unbalanced_entry"))
                .andExpect(jsonPath("$.error.details.ledger_reason").value(containsString(
                        "off by 500")));

        // nothing posted, the entry stays proposed, the break stays open
        assertThat(env.ledger.committedCount()).isZero();
        assertThat(env.compensations.findById(compensationId).orElseThrow().state().toString())
                .isEqualTo("PROPOSED");
        assertThat(env.breaks.findById(breakId).orElseThrow().state().toString())
                .isEqualTo("OPEN");
    }

    @Test
    void anUnknownCompensationOrABlankApproverIs404Or400() throws Exception {
        mockMvc.perform(post("/internal/recon/compensations/{id}/approve", "cmp_unknown")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"ops.bob\"}"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("not_found"));

        mockMvc.perform(post("/internal/recon/compensations/{id}/approve", compensationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\" \"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("validation_error"));

        mockMvc.perform(post("/internal/recon/compensations/{id}/approve", compensationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        assertThat(env.ledger.attempts()).isZero();
    }

    @Test
    void aTerminalBreakBlocksTheApprovalBeforeTheLedgerIsTouched() throws Exception {
        // OPEN → INVESTIGATING → RESOLVED (the legal manual path)
        env.transitionBreak.transition(breakId, "investigating", "ops.alice",
                "hypothesis: statement re-issued");
        env.transitionBreak.transition(breakId, "resolved", "ops.alice", "matched by re-run");

        mockMvc.perform(post("/internal/recon/compensations/{id}/approve", compensationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"ops.bob\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("state_conflict"))
                .andExpect(jsonPath("$.error.message").value(
                        containsString("only an open or investigating break")));

        assertThat(env.ledger.attempts()).isZero();
    }

    @Test
    void aReversalProposalExecutesAsAReversalPosting() throws Exception {
        // commit the prior entry so the reversal pairs against it — the
        // ledger's own entry id is the reversal reference (never invented)
        UUID priorEntry = ((com.sharkpay.reconciliation.ports.LedgerPort.PostingResult.Committed)
                env.ledger.post(com.sharkpay.reconciliation.ports.LedgerPort.LedgerPosting.of(
                        "ops:adj:prior", com.sharkpay.reconciliation.ports.LedgerPort.Source.OPS,
                        UUID.randomUUID(),
                        com.sharkpay.reconciliation.ports.LedgerPort.EntryType.ADJUSTMENT,
                        "prior", portLegs()))).entryId();

        String reversal = env.proposeCompensation.propose("key-rev", breakId, "ops.alice",
                "corrects the wrong compensation", legs(), priorEntry).entry().id();

        mockMvc.perform(post("/internal/recon/compensations/{id}/approve", reversal)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"ops.bob\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.reverses_entry_id").value(priorEntry.toString()));
    }

    private void approve(String approver) throws Exception {
        mockMvc.perform(post("/internal/recon/compensations/{id}/approve", compensationId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"" + approver + "\"}"))
                .andExpect(status().isOk());
    }

    private static java.util.List<com.sharkpay.reconciliation.domain.CompensationLeg> legs() {
        return java.util.List.of(
                new com.sharkpay.reconciliation.domain.CompensationLeg("suspense:recon:KES",
                        com.sharkpay.reconciliation.domain.PostingDirection.DEBIT,
                        com.sharkpay.money.Money.of(500, "KES")),
                new com.sharkpay.reconciliation.domain.CompensationLeg(
                        "honeycoin:settlement:KES",
                        com.sharkpay.reconciliation.domain.PostingDirection.CREDIT,
                        com.sharkpay.money.Money.of(500, "KES")));
    }

    /** The same legs as the use case's toPosting maps them onto the port. */
    private static java.util.List<com.sharkpay.reconciliation.ports.LedgerPort.Leg> portLegs() {
        return legs().stream()
                .map(leg -> new com.sharkpay.reconciliation.ports.LedgerPort.Leg(leg.accountRef(),
                        com.sharkpay.reconciliation.ports.LedgerPort.Direction.valueOf(
                                leg.direction().name()), leg.amount()))
                .toList();
    }
}
