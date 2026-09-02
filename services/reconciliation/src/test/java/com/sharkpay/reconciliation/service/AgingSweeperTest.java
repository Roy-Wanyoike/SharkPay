package com.sharkpay.reconciliation.service;

import com.sharkpay.reconciliation.config.ReconConfig;
import com.sharkpay.reconciliation.domain.BreakState;
import com.sharkpay.reconciliation.domain.ReconciliationStateException;
import com.sharkpay.reconciliation.events.ReconEvents;
import com.sharkpay.reconciliation.testsupport.ReconTestEnv;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.annotation.Scheduled;

import java.lang.reflect.Method;
import java.time.Duration;
import java.util.NoSuchElementException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The @Scheduled RB-7 sweeper bean (AgingSweeper): delegates to the use
 * case on the fixed-delay cadence wired in application.yml, so escalations
 * happen without an external trigger.
 */
class AgingSweeperTest {

    private ReconTestEnv env;
    private AgingSweeper sweeper;

    @BeforeEach
    void setUp() {
        env = new ReconTestEnv();
        sweeper = new AgingSweeper(env.sweepAging);
        env.seedProviderLine("hc_ghost", "CONFIRMED", 2_000, 0);
    }

    @Test
    void theSweeperDelegatesToTheUseCase() {
        env.triggerDefault("key-run"); // 1 break, FRESH
        env.clock.advance(Duration.ofHours(25));

        sweeper.sweep();

        assertThat(env.events.eventsOfType(ReconEvents.BREAK_ESCALATED)).hasSize(1);
        assertThat(env.breaks.listActive().get(0).bucket())
                .isEqualTo(com.sharkpay.reconciliation.domain.AgingBucket.AGING);
    }

    @Test
    void theSweeperRequiresItsUseCase() {
        assertThatThrownBy(() -> new AgingSweeper(null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("sweepAgingBreaksUseCase is required");
    }

    @Test
    void theCadencePropertyMatchesApplicationYmlAndTheEnvVarOverride() throws Exception {
        Method sweep = AgingSweeper.class.getMethod("sweep");
        Scheduled scheduled = sweep.getAnnotation(Scheduled.class);
        assertThat(scheduled).isNotNull();
        // application.yml: recon.aging-sweep-interval-ms (flat key — the
        // exact name @Scheduled resolves), default 5 minutes, overridable
        // via RECON_AGING_SWEEP_INTERVAL_MS
        assertThat(scheduled.fixedDelayString())
                .isEqualTo("${recon.aging-sweep-interval-ms:300000}");
    }

    @Test
    void theSweeperIsAWiringDetailOfTheConfigNotADomainRule() {
        // the production config builds the same sweeper from the use-case bean
        ReconConfig config = new ReconConfig();
        var useCase = config.sweepAgingBreaksUseCase(env.breaks, env.events, env.eventFactory,
                env.clock);
        AgingSweeper productionSweeper = new AgingSweeper(useCase);
        env.clock.advance(Duration.ofHours(25));
        productionSweeper.sweep();
        assertThat(env.events.eventsOfType(ReconEvents.BREAK_ESCALATED)).isEmpty();
        // (no break exists yet — the sweeper is a pure pass-through)
    }
}
