package com.sharkpay.payouts.service;

import com.sharkpay.payouts.ports.ProviderGatewayPort;
import com.sharkpay.payouts.testsupport.PayoutsTestEnv;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * PayoutSweeper + Ids: the tick composes the three background use-cases and
 * reports their sub-reports; public ids match the contract patterns and the
 * paired UUID doubles as the ledger source_ref.
 */
class SweeperAndIdsTest {

    @Test
    void oneTickReleasesSweepsAndPollsComposingTheSubReports() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        env.createDefaultPayout(); // due for release

        PayoutSweeper.TickReport report = env.sweeper.runTick();

        assertThat(report.release().considered()).isEqualTo(1);
        assertThat(report.release().submitted()).isEqualTo(1);
        assertThat(report.expiry().considered()).isZero();
        assertThat(report.expiry().cancelled()).isZero();
        assertThat(report.poll().inFlight()).isEqualTo(1);
        assertThat(report.poll().evaluated()).isEqualTo(1); // PENDING → SENT
        assertThat(env.payouts.countByState(com.sharkpay.payouts.domain.PayoutState.SENT))
                .isEqualTo(1);
    }

    @Test
    void anEmptyTickReportsZerosEverywhere() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        PayoutSweeper.TickReport report = env.sweeper.runTick();
        assertThat(report.release().considered()).isZero();
        assertThat(report.expiry().considered()).isZero();
        assertThat(report.poll().inFlight()).isZero();
    }

    @Test
    void theSweeperValidatesItsDependencies() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        assertThatThrownBy(() -> new PayoutSweeper(null, env.expireOverdue, env.pollInFlight))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PayoutSweeper(env.releaseDue, null, env.pollInFlight))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new PayoutSweeper(env.releaseDue, env.expireOverdue, null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theTickDrivesTheFullLifecycleAcrossClockAdvances() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        var payout = env.createDefaultPayout();

        // tick 1: release → PROCESSING, then the same tick's poll applies
        // PENDING (the gateway default) → SENT
        env.sweeper.runTick();
        assertThat(env.payouts.findById(payout.id()).orElseThrow().state())
                .isEqualTo(com.sharkpay.payouts.domain.PayoutState.SENT);

        // tick 2: poll applies PENDING → SENT
        env.gateway.statusFor(ExpirePayoutsUseCase.providerRefOf(
                        env.payouts.findById(payout.id()).orElseThrow()),
                ProviderGatewayPort.ProviderStatus.PENDING);
        env.sweeper.runTick();
        assertThat(env.payouts.findById(payout.id()).orElseThrow().state())
                .isEqualTo(com.sharkpay.payouts.domain.PayoutState.SENT);

        // tick 3: poll applies SUCCEEDED → settle
        env.gateway.statusFor(ExpirePayoutsUseCase.providerRefOf(
                        env.payouts.findById(payout.id()).orElseThrow()),
                ProviderGatewayPort.ProviderStatus.SUCCEEDED);
        env.sweeper.runTick();
        assertThat(env.payouts.findById(payout.id()).orElseThrow().state())
                .isEqualTo(com.sharkpay.payouts.domain.PayoutState.SUCCEEDED);
        assertThat(env.ledger.journal()).hasSize(2); // hold + settle
    }

    @Test
    void repeatedTicksNeverReSubmitAnInFlightPayout() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        var payout = env.createDefaultPayout();

        env.sweeper.runTick(); // → PROCESSING
        env.sweeper.runTick(); // poll PENDING → SENT
        env.sweeper.runTick();

        // exactly ONE submission for the payout across all ticks
        assertThat(env.gateway.initiateAttemptsFor(payout.id())).isEqualTo(1);
        assertThat(env.gateway.initiateEffectsFor("payouts:" + payout.id() + ":submit"))
                .isEqualTo(1);
        assertThat(env.ledger.journal()).hasSize(1); // the hold only
    }

    @Test
    void transferIdsMatchTheContractPatternAndCarryTheLedgerRef() {
        for (int i = 0; i < 25; i++) {
            Ids.Identity identity = Ids.newTransferId();
            assertThat(identity.publicId()).matches("^trf_[0-9A-Za-z]{20,}$");
            assertThat(identity.publicId()).isEqualTo("trf_" + identity.internalRef().toString()
                    .replace("-", ""));
            assertThat(identity.internalRef()).isNotNull();
        }
    }

    @Test
    void payoutIdsMatchTheContractPatternAndCarryTheLedgerRef() {
        for (int i = 0; i < 25; i++) {
            Ids.Identity identity = Ids.newPayoutId();
            assertThat(identity.publicId()).matches("^pot_[0-9A-Za-z]{20,}$");
            assertThat(identity.publicId()).isEqualTo("pot_" + identity.internalRef().toString()
                    .replace("-", ""));
        }
    }

    @Test
    void requestIdsMatchTheEnvelopePattern() {
        for (int i = 0; i < 25; i++) {
            assertThat(Ids.requestId()).matches("^req_[0-9A-Za-z]+$");
        }
    }

    @Test
    void idsAreUnique() {
        var ids = new java.util.HashSet<String>();
        var refs = new java.util.HashSet<UUID>();
        for (int i = 0; i < 100; i++) {
            ids.add(Ids.newPayoutId().publicId());
            ids.add(Ids.newTransferId().publicId());
            ids.add(Ids.requestId());
            refs.add(Ids.newPayoutId().internalRef());
        }
        assertThat(ids).hasSize(300); // 100 payout ids + 100 transfer ids + 100 request ids
        assertThat(refs).hasSize(100);
    }

    @Test
    void gettersAndReadSideUseCasesValidateTheirArguments() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        var payout = env.createDefaultPayout();
        assertThat(env.getPayout.get(payout.id()).id()).isEqualTo(payout.id());
        assertThat(env.getPayout.get("  " + payout.id() + "  ").id()).isEqualTo(payout.id()); // trimmed
        assertThatThrownBy(() -> env.getPayout.get(null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> env.getPayout.get("pot_0000000000000000000000000"))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining("not found");
        assertThatThrownBy(() -> new GetPayoutUseCase(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theReleaseBatchQueryMirrorsTheJpaPartialIndexSemantics() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        // three payouts with staggered executeAfter
        env.createPayout.create("k1", PayoutsTestEnv.WALLET, 1_000L, "KES",
                PayoutsTestEnv.mpesaDestination(), null, Map.of(), null,
                PayoutsTestEnv.START.plusSeconds(5));
        env.createPayout.create("k2", PayoutsTestEnv.WALLET, 1_000L, "KES",
                PayoutsTestEnv.mpesaDestination(), null, Map.of(), null,
                PayoutsTestEnv.START.plusSeconds(3));
        env.createPayout.create("k3", PayoutsTestEnv.WALLET, 1_000L, "KES",
                PayoutsTestEnv.mpesaDestination(), null, Map.of(), null,
                PayoutsTestEnv.START.plusSeconds(7));

        env.clock.advance(Duration.ofSeconds(10));
        var due = env.payouts.findDueForRelease(env.clock.instant(), 10);
        assertThat(due).extracting(com.sharkpay.payouts.domain.Payout::executeAfter)
                .containsExactly(PayoutsTestEnv.START.plusSeconds(3),
                        PayoutsTestEnv.START.plusSeconds(5),
                        PayoutsTestEnv.START.plusSeconds(7));
        assertThat(env.payouts.findExpired(env.clock.instant().plus(Duration.ofHours(1)), 10))
                .hasSize(3);
        assertThat(env.payouts.findInFlight(10)).isEmpty();
    }
}
