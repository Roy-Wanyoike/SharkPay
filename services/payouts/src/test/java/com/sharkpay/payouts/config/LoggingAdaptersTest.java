package com.sharkpay.payouts.config;

import com.sharkpay.payouts.events.CloudEvent;
import com.sharkpay.payouts.events.PayoutEvents;
import com.sharkpay.payouts.events.TransferEvents;
import com.sharkpay.payouts.ports.ProviderGatewayPort;
import com.sharkpay.payouts.testsupport.PayoutsTestEnv;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The logging placeholders are the production adapters until the real ones
 * land (ADR 003 §3): the event publisher must never throw on any envelope
 * the service emits (payout lifecycle + transfer terminal events), and the
 * scheduler port must accept wake-up requests and cancellations — the
 * polling sweeper remains the release safety net, so a lost log line is
 * never a lost release.
 */
class LoggingAdaptersTest {

    @Test
    void publishNeverThrowsOnEveryPayoutAndTransferEnvelope() {
        PayoutsTestEnv env = new PayoutsTestEnv();
        LoggingEventPublisher publisher = new LoggingEventPublisher();

        // drive the full catalog: created → processing → sent → succeeded
        var payout = env.createDefaultPayout();
        env.clock.advance(Duration.ofSeconds(1));
        env.releaseDue.releaseDue();
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.PENDING,
                null, null, null, null, null);
        env.providerResults.ingest(payout.id(), ProviderGatewayPort.ProviderStatus.SUCCEEDED,
                null, null, null, null, null);
        // …and the failed / returned terminals via a second payout
        var other = env.createPayout("k2");
        env.clock.advance(Duration.ofSeconds(1));
        env.releaseDue.releaseDue();
        env.providerResults.ingest(other.id(), ProviderGatewayPort.ProviderStatus.SUCCEEDED,
                null, null, null, null, null);
        env.providerResults.ingest(other.id(), ProviderGatewayPort.ProviderStatus.RETURNED,
                null, "msisdn_not_registered", 500_000L, "KES", "ret-1");
        // transfer terminal events
        env.createTransfer("transfer-key", 10_000L);
        env.ledger.rejectPrefix("transfers:");
        env.createTransfer.create("transfer-key-2", PayoutsTestEnv.WALLET,
                PayoutsTestEnv.OTHER_WALLET, 1_000L, "KES", Map.of());

        for (CloudEvent event : env.events.events()) {
            assertThatCode(() -> publisher.publish(event))
                    .as("event %s must log without throwing", event.type())
                    .doesNotThrowAnyException();
        }
        // at least one of every catalog type went through the placeholder
        org.assertj.core.api.Assertions.assertThat(env.events.eventsOfType(PayoutEvents.CREATED))
                .isNotEmpty();
        org.assertj.core.api.Assertions.assertThat(env.events.eventsOfType(PayoutEvents.RETURNED))
                .isNotEmpty();
        org.assertj.core.api.Assertions.assertThat(
                        env.events.eventsOfType(TransferEvents.SUCCEEDED)).isNotEmpty();
        org.assertj.core.api.Assertions.assertThat(env.events.eventsOfType(TransferEvents.FAILED))
                .isNotEmpty();
    }

    @Test
    void publishAcceptsAnArbitraryWellFormedEnvelope() {
        LoggingEventPublisher publisher = new LoggingEventPublisher();
        CloudEvent event = new CloudEvent("0192a7cf-1e2f-9a3b-9c4d-8e6f7a8b9c0d",
                PayoutEvents.CREATED, CloudEvent.SPECVERSION, CloudEvent.PAYOUTS_SOURCE,
                "pot_0123456789abcdef0123456789abcdef",
                java.time.Instant.parse("2026-09-01T10:00:06Z"), Map.of("k", "v"));
        assertThatCode(() -> publisher.publish(event)).doesNotThrowAnyException();
    }

    @Test
    void schedulerRequestsAndCancellationsNeverThrow() {
        LoggingSchedulerPort scheduler = new LoggingSchedulerPort();
        assertThatCode(() -> scheduler.requestRelease("pot_0123456789abcdef0123456789abcdef",
                java.time.Instant.parse("2026-09-01T10:00:30Z")))
                .doesNotThrowAnyException();
        assertThatCode(() -> scheduler.cancelRelease("pot_0123456789abcdef0123456789abcdef"))
                .doesNotThrowAnyException();
    }
}
