package com.sharkpay.reconciliation.config;

import com.sharkpay.reconciliation.events.CloudEvent;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The production event publisher until the NATS/Kafka CloudEvent adapter
 * lands: every envelope is logged (structured line with type, id, subject,
 * occurred_at) and publishing never throws — an event sink outage must not
 * break the recon run.
 */
class LoggingEventPublisherTest {

    @Test
    void publishingLogsTheEnvelopeWithoutThrowing() {
        CapturingHandler capture = new CapturingHandler();
        Logger logger = Logger.getLogger(LoggingEventPublisher.class.getName());
        logger.setUseParentHandlers(false);
        logger.addHandler(capture);
        try {
            LoggingEventPublisher publisher = new LoggingEventPublisher();
            CloudEvent event = new CloudEvent("0192a7cd-9c0d-9e1f-9a2b-8c4d5e6f7a8b",
                    "recon.break.detected.v1", CloudEvent.SPECVERSION, CloudEvent.SOURCE,
                    "brk_01HZWR4Z7K8Q2N5M9X3V1B6Y0A", Instant.parse("2026-09-01T10:00:30Z"),
                    "payload");

            assertThatCode(() -> publisher.publish(event)).doesNotThrowAnyException();

            assertThat(capture.records).isNotEmpty();
            assertThat(capture.lastMessage())
                    .contains("recon.break.detected.v1")
                    .contains("0192a7cd-9c0d-9e1f-9a2b-8c4d5e6f7a8b")
                    .contains("brk_01HZWR4Z7K8Q2N5M9X3V1B6Y0A")
                    .contains("2026-09-01T10:00:30Z");
        } finally {
            logger.removeHandler(capture);
            logger.setUseParentHandlers(true);
        }
    }

    private static final class CapturingHandler extends Handler {

        private final List<LogRecord> records = new java.util.concurrent.CopyOnWriteArrayList<>();

        @Override
        public void publish(LogRecord record) {
            if (record.getLevel().intValue() >= Level.INFO.intValue()) {
                records.add(record);
            }
        }

        String lastMessage() {
            return records.get(records.size() - 1).getMessage();
        }

        @Override
        public void flush() {
        }

        @Override
        public void close() {
        }
    }
}
