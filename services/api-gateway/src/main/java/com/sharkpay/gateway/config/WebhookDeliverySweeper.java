package com.sharkpay.gateway.config;

import com.sharkpay.gateway.service.DeliveryAttemptUseCase;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import java.time.Clock;

/**
 * The delivery sweeper: drains due pending webhook deliveries on a fixed
 * cadence (the retry schedule — 1 m → 1 h capped — is per delivery, set by
 * the backoff policy; the sweep just picks up whatever became due). Sweep
 * logs are the delivery worker's heartbeat.
 */
public final class WebhookDeliverySweeper {

    private static final Logger log = LoggerFactory.getLogger(WebhookDeliverySweeper.class);

    private final DeliveryAttemptUseCase worker;
    private final Clock clock;

    public WebhookDeliverySweeper(DeliveryAttemptUseCase worker, Clock clock) {
        this.worker = worker;
        this.clock = clock;
    }

    /** One sweep (default every 30 s; tunable via gateway.webhook.sweep-ms). */
    @Scheduled(fixedDelayString = "${gateway.webhook.sweep-ms:30000}")
    public void sweep() {
        DeliveryAttemptUseCase.Summary summary = worker.processDue(clock.instant());
        if (summary.attempted() > 0) {
            log.info("webhook sweep attempted={} delivered={} dead={} autoPaused={}",
                    summary.attempted(), summary.delivered(), summary.dead(),
                    summary.autoPaused());
        }
    }
}
