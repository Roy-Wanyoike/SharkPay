package com.sharkpay.wallet.config;

import com.sharkpay.wallet.domain.Source;
import com.sharkpay.wallet.domain.Wallet;
import com.sharkpay.wallet.events.CloudEvent;
import com.sharkpay.wallet.events.WalletEvents;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatCode;

/**
 * The logging publisher is the production EventPublisher placeholder until
 * the NATS/Kafka adapter lands (ADR 003): publishing must never throw and
 * must accept every envelope the service emits.
 */
class LoggingEventPublisherTest {

    @Test
    void publishNeverThrowsOnEveryEmittedEnvelope() {
        LoggingEventPublisher publisher = new LoggingEventPublisher();
        Instant at = Instant.parse("2026-09-01T10:00:00Z");
        Wallet wallet = Wallet.newWallet("wal_" + "a".repeat(32), UUID.randomUUID(), "KES",
                UUID.randomUUID(), at);
        com.sharkpay.wallet.domain.Hold hold = com.sharkpay.wallet.domain.Hold.place(
                "hld_" + "b".repeat(32), wallet.id(),
                com.sharkpay.money.Money.of(500, "KES"), Source.PAYMENTS, UUID.randomUUID(),
                "reason", at);

        assertThatCode(() -> publisher.publish(WalletEvents.walletStateChanged(wallet, null,
                "wallet created", at))).doesNotThrowAnyException();
        assertThatCode(() -> publisher.publish(WalletEvents.holdPlaced(wallet, hold, at)))
                .doesNotThrowAnyException();
        assertThatCode(() -> publisher.publish(WalletEvents.holdReleased(wallet, hold, at)))
                .doesNotThrowAnyException();
        assertThatCode(() -> publisher.publish(WalletEvents.holdCaptured(wallet, hold, at)))
                .doesNotThrowAnyException();
        assertThatCode(() -> publisher.publish(WalletEvents.balanceChanged(wallet,
                new com.sharkpay.wallet.domain.Balances(
                        com.sharkpay.money.Money.of(1_000, "KES"),
                        com.sharkpay.money.Money.zero("KES"),
                        com.sharkpay.money.Money.zero("KES")),
                Source.PAYMENTS, UUID.randomUUID(), at))).doesNotThrowAnyException();
    }

    @Test
    void publishAcceptsAnArbitraryWellFormedEnvelope() {
        LoggingEventPublisher publisher = new LoggingEventPublisher();
        CloudEvent event = new CloudEvent("0192a7cf-1e2f-9a3b-9c4d-8e6f7a8b9c0d",
                "wallet.balance.changed.v1", CloudEvent.SPECVERSION, CloudEvent.SOURCE,
                "wal_" + "c".repeat(32), Instant.parse("2026-09-01T10:00:06Z"),
                java.util.Map.of("wallet_id", "wal_" + "c".repeat(32)));
        assertThatCode(() -> publisher.publish(event)).doesNotThrowAnyException();
    }
}
