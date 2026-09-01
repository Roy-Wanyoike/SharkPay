package com.sharkpay.fx.config;

import com.sharkpay.fx.domain.Quote;
import com.sharkpay.fx.events.CloudEvent;
import com.sharkpay.fx.events.FxEvents;
import com.sharkpay.fx.testsupport.FxTestEnv;
import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The logging publisher is the production EventPublisher placeholder until
 * the Kafka adapter lands (ADR 003): publishing must never throw and must
 * accept every envelope the service emits.
 */
class LoggingEventPublisherTest {

    private final FxTestEnv env = new FxTestEnv();

    @Test
    void publishNeverThrowsOnEveryEmittedEnvelope() {
        Quote quote = env.newLockedQuote("USD", "KES", 10_000);
        env.convert.convert("logging-publisher-1", quote.id(), "wallet/src-USD", "wallet/dst-KES");
        LoggingEventPublisher publisher = new LoggingEventPublisher();

        for (CloudEvent event : env.events.events()) {
            assertThatCode(() -> publisher.publish(event)).doesNotThrowAnyException();
        }
    }

    @Test
    void publishAcceptsAnArbitraryWellFormedEnvelope() {
        LoggingEventPublisher publisher = new LoggingEventPublisher();
        CloudEvent event = new CloudEvent("0192a7cf-1e2f-9a3b-9c4d-8e6f7a8b9c0d",
                FxEvents.CONVERSION_EXECUTED, CloudEvent.SPECVERSION, CloudEvent.SOURCE,
                "cnv_" + "c".repeat(26), Instant.parse("2026-09-01T10:00:06Z"),
                java.util.Map.of("conversion_id", "cnv_" + "c".repeat(26)));
        assertThatCode(() -> publisher.publish(event)).doesNotThrowAnyException();
    }
}
