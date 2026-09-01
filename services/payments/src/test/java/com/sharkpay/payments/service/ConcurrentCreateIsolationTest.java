package com.sharkpay.payments.service;

import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.testsupport.PaymentsTestEnv;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * G2 money-safety under real concurrency: distinct Idempotency-Keys creating
 * payments in parallel are fully isolated — every key mints exactly one
 * intent, one hold, one wire initiation, and its own replay returns its own
 * intent (no cross-key interference anywhere in the synchronous prefix).
 */
class ConcurrentCreateIsolationTest {

    private static final int THREADS = 12;

    private final PaymentsTestEnv env = new PaymentsTestEnv();

    @Test
    void concurrentDistinctKeysAreFullyIsolated() throws Exception {
        UUID principal = env.principals.principalId();
        ExecutorService pool = Executors.newFixedThreadPool(THREADS);
        CyclicBarrier barrier = new CyclicBarrier(THREADS);
        try {
            List<Future<PaymentIntent>> creations = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                final String key = "concurrent-key-" + i;
                creations.add(pool.submit(() -> {
                    barrier.await(); // maximize interleaving
                    return env.createPayment.create(key, principal, 150_000L, "KES",
                            PaymentsTestEnv.WALLET, "honeycoin", Map.of(), null).intent();
                }));
            }

            List<PaymentIntent> intents = new ArrayList<>();
            for (Future<PaymentIntent> creation : creations) {
                intents.add(creation.get(30, TimeUnit.SECONDS));
            }

            // one intent / hold / initiation / key-claim per key, all distinct
            assertThat(intents).hasSize(THREADS);
            assertThat(intents.stream().map(PaymentIntent::id).distinct()).hasSize(THREADS);
            assertThat(intents.stream().map(PaymentIntent::internalId).distinct())
                    .hasSize(THREADS);
            assertThat(env.payments.count()).isEqualTo(THREADS);
            assertThat(env.idempotency.count()).isEqualTo(THREADS);
            assertThat(env.walletHolds.placedHolds()).hasSize(THREADS);
            assertThat(env.gateway.initiations()).hasSize(THREADS);
            assertThat(env.gateway.initiatedByKey()).hasSize(THREADS);
            assertThat(env.ledger.totalEffects()).isEqualTo(THREADS); // one HOLD each
            assertThat(intents).allSatisfy(intent -> {
                assertThat(intent.state()).isEqualTo(PaymentState.PENDING_PROVIDER);
                assertThat(intent.holdId()).isNotBlank();
                assertThat(env.ledger.effectCount(intent.internalId(),
                        com.sharkpay.payments.ports.LedgerPort.EntryType.HOLD)).isEqualTo(1);
            });

            // every key replays its own intent with no second effect — also
            // concurrently, after the creations settled
            List<Future<PaymentIntent>> replays = new ArrayList<>();
            for (int i = 0; i < THREADS; i++) {
                final String key = "concurrent-key-" + i;
                replays.add(pool.submit(() -> env.createPayment
                        .create(key, principal, 150_000L, "KES", PaymentsTestEnv.WALLET,
                                "honeycoin", Map.of(), null)
                        .intent()));
            }
            List<PaymentIntent> replayed = new ArrayList<>();
            for (Future<PaymentIntent> replay : replays) {
                replayed.add(replay.get(30, TimeUnit.SECONDS));
            }

            assertThat(replayed).containsExactlyInAnyOrderElementsOf(intents);
            assertThat(env.payments.count()).isEqualTo(THREADS);
            assertThat(env.walletHolds.placedHolds()).hasSize(THREADS);
            assertThat(env.gateway.initiations()).hasSize(THREADS);
            assertThat(env.ledger.totalEffects()).isEqualTo(THREADS);
        } finally {
            pool.shutdownNow();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
