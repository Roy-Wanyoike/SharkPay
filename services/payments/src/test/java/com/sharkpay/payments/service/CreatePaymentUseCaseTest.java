package com.sharkpay.payments.service;

import com.sharkpay.payments.domain.IdempotencyConflictException;
import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.domain.RiskReviewException;
import com.sharkpay.payments.domain.UnknownWalletException;
import com.sharkpay.payments.domain.UnsupportedCurrencyException;
import com.sharkpay.payments.ports.ProviderGatewayPort;
import com.sharkpay.payments.ports.ProviderUnavailableException;
import com.sharkpay.payments.ports.RiskPort;
import com.sharkpay.payments.testsupport.PaymentsTestEnv;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * POST /payments semantics (payments.yaml createPayment): the synchronous
 * risk → hold → route → initiate prefix, idempotency replay/conflict,
 * risk outcomes, provider outcomes and the G2 money-safety guarantees.
 */
class CreatePaymentUseCaseTest {

    private final PaymentsTestEnv env = new PaymentsTestEnv();

    @Test
    void happyPathRunsTheWholeSynchronousPrefix() {
        PaymentIntent intent = env.createDefault();

        assertThat(intent.state()).isEqualTo(PaymentState.PENDING_PROVIDER);
        assertThat(intent.amount().amountMinor()).isEqualTo(150_000);
        assertThat(intent.fee().amountMinor()).isEqualTo(750); // 50 bps exact
        assertThat(intent.idempotencyKey()).isEqualTo("key-1");
        assertThat(intent.expiresAt()).isEqualTo(PaymentsTestEnv.START.plusSeconds(900));

        // risk ran pre-authorization with the pending payment reference
        assertThat(env.risk.evaluations()).hasSize(1);
        assertThat(env.risk.evaluations().get(0).phase()).isEqualTo(RiskPort.Phase.PRE_AUTHORIZATION);
        assertThat(env.risk.evaluations().get(0).paymentId()).isEqualTo("pay_pending_key-1");

        // hold placed: ledger HOLD entry + wallet hold, state alignment (§7.4)
        assertThat(env.ledger.effectCount(intent.internalId(),
                com.sharkpay.payments.ports.LedgerPort.EntryType.HOLD)).isEqualTo(1);
        assertThat(env.walletHolds.hasHold(intent.internalId())).isTrue();
        assertThat(intent.holdId()).startsWith("hld_");

        // routed + initiated, provider refs recorded, idempotency on internal id
        assertThat(intent.provider()).isEqualTo("honeycoin");
        assertThat(intent.providerRef()).isNotBlank();
        assertThat(env.gateway.initiatedByKey()).containsKey(intent.internalId().toString());
        assertThat(env.gateway.initiations()).hasSize(1);

        // lifecycle handed off exactly once; events created + pending_provider
        assertThat(env.lifecycle.startsOf(intent.id())).isEqualTo(1);
        assertThat(env.events.eventsOfType("payments.payment.created.v1")).hasSize(1);
        assertThat(env.events.eventsOfType("payments.payment.pending_provider.v1")).hasSize(1);

        // the transitions were persisted: CREATED + PENDING_PROVIDER
        assertThat(env.payments.transitionsOf(intent.id()))
                .extracting(com.sharkpay.payments.domain.StateTransition::to)
                .containsExactly(PaymentState.CREATED, PaymentState.PENDING_PROVIDER);
    }

    @Test
    void replayWithSameKeyAndPayloadReturnsTheOriginalWithNoSecondEffect() {
        PaymentIntent first = env.create("key-1");
        env.events.reset();

        var result = env.createPayment.create("key-1", env.principals.principalId(), 150_000L,
                "KES", PaymentsTestEnv.WALLET, "honeycoin", Map.of(), null);

        assertThat(result.replay()).isTrue();
        assertThat(result.intent().id()).isEqualTo(first.id());
        assertThat(env.payments.count()).isEqualTo(1);
        assertThat(env.walletHolds.placedHolds()).hasSize(1); // no double hold
        assertThat(env.ledger.totalEffects()).isEqualTo(1);   // HOLD only
        assertThat(env.gateway.initiations()).hasSize(1);     // no double initiate
        assertThat(env.risk.evaluations()).hasSize(1);        // risk not re-run on replay
        assertThat(env.events.events()).isEmpty();            // no duplicate events
    }

    @Test
    void sameKeyWithDifferentPayloadIsAConflict() {
        env.create("key-1");
        assertThatThrownBy(() -> env.createPayment.create("key-1", env.principals.principalId(),
                999_999L, "KES", PaymentsTestEnv.WALLET, "honeycoin", Map.of(), null))
                .isInstanceOf(IdempotencyConflictException.class)
                .hasMessageContaining("different request payload");
        assertThat(env.payments.count()).isEqualTo(1); // nothing new persisted
    }

    @Test
    void fingerprintTreatsExplicitDefaultExpiryAsTheDefault() {
        env.create("key-1");
        var result = env.createPayment.create("key-1", env.principals.principalId(), 150_000L,
                "KES", PaymentsTestEnv.WALLET, "honeycoin", Map.of(), 900);
        assertThat(result.replay()).isTrue();
    }

    @Test
    void unknownCurrencyIsABusinessRejection() {
        assertThatThrownBy(() -> env.createPayment.create("key-1", env.principals.principalId(),
                1_000L, "XYZ", PaymentsTestEnv.WALLET, null, Map.of(), null))
                .isInstanceOf(UnsupportedCurrencyException.class);
        assertThat(env.payments.count()).isZero();
        assertThat(env.idempotency.count()).isZero(); // key not consumed
    }

    @Test
    void currencyIsCanonicalisedCaseInsensitively() {
        PaymentIntent intent = env.createPayment.create("key-1", env.principals.principalId(),
                1_000L, "kes", PaymentsTestEnv.WALLET, null, Map.of(), null).intent();
        assertThat(intent.amount().currency()).isEqualTo("KES");
        assertThat(intent.rail()).isEqualTo(com.sharkpay.payments.domain.Rail.HONEYCOIN);
    }

    @Test
    void railWithoutAScheduleIsABusinessRejection() {
        assertThatThrownBy(() -> env.createPayment.create("key-1", env.principals.principalId(),
                1_000L, "USDC", PaymentsTestEnv.WALLET, "bank", Map.of(), null))
                .isInstanceOf(UnsupportedCurrencyException.class)
                .hasMessageContaining("bank");
    }

    @Test
    void noRailHintPicksTheDeterministicDefaultRail() {
        PaymentIntent intent = env.createPayment.create("key-1", env.principals.principalId(),
                1_000L, "USD", PaymentsTestEnv.WALLET, null, Map.of(), null).intent();
        assertThat(intent.rail()).isEqualTo(com.sharkpay.payments.domain.Rail.BANK);
    }

    @Test
    void unknownDestinationWalletIsANotFound() {
        assertThatThrownBy(() -> env.createPayment.create("key-1", env.principals.principalId(),
                1_000L, "KES", "wal_0123456789abcdef0123456789abcdff", "honeycoin", Map.of(),
                null))
                .isInstanceOf(UnknownWalletException.class);
        assertThat(env.walletHolds.unknownWalletProbes())
                .contains("wal_0123456789abcdef0123456789abcdff");
    }

    @Test
    void riskDenyPersistsABlockedIntentWithNoMoneyMoved() {
        env.risk.next(com.sharkpay.payments.fakes.FakeRiskPort.deny("velocity spike"));
        PaymentIntent intent = env.createDefault();

        assertThat(intent.state()).isEqualTo(PaymentState.BLOCKED);
        assertThat(env.walletHolds.placedHolds()).isEmpty();     // no hold
        assertThat(env.ledger.totalEffects()).isZero();          // no entries
        assertThat(env.lifecycle.startsOf(intent.id())).isZero();// no orchestration
        assertThat(env.gateway.initiations()).isEmpty();         // no wire call
        assertThat(env.events.eventsOfType("payments.payment.created.v1")).hasSize(1);
        assertThat(env.payments.transitionsOf(intent.id()))
                .extracting(com.sharkpay.payments.domain.StateTransition::to)
                .containsExactly(PaymentState.CREATED, PaymentState.BLOCKED);
    }

    @Test
    void riskReviewRejectsWithoutPersistingOrConsumingTheKey() {
        env.risk.next(com.sharkpay.payments.fakes.FakeRiskPort.review("manual review"));
        assertThatThrownBy(() -> env.createDefault())
                .isInstanceOf(RiskReviewException.class)
                .hasMessageContaining("manual review");

        assertThat(env.payments.count()).isZero();       // nothing persisted
        assertThat(env.idempotency.count()).isZero();    // key survives for the retry
        assertThat(env.walletHolds.placedHolds()).isEmpty();

        // the caller retries the same request after the review clears → success
        PaymentIntent retry = env.createDefault();
        assertThat(retry.state()).isEqualTo(PaymentState.PENDING_PROVIDER);
    }

    @Test
    void providerRejectionCompensatesAndFailsTheIntent() {
        env.gateway.rejectNextInitiations(1);
        PaymentIntent intent = env.createDefault();

        assertThat(intent.state()).isEqualTo(PaymentState.FAILED);
        assertThat(intent.failureReason()).contains("rail rejected");
        // compensation: hold released + ledger RELEASE entry, exactly once
        assertThat(env.walletHolds.wasReleased(intent.internalId())).isTrue();
        assertThat(env.ledger.effectCount(intent.internalId(),
                com.sharkpay.payments.ports.LedgerPort.EntryType.RELEASE)).isEqualTo(1);
        assertThat(env.ledger.effectCount(intent.internalId(),
                com.sharkpay.payments.ports.LedgerPort.EntryType.CAPTURE)).isZero();
        assertThat(env.lifecycle.startsOf(intent.id())).isZero();
        assertThat(env.events.eventsOfType("payments.payment.failed.v1")).hasSize(1);
    }

    @Test
    void noEligibleProviderCompensatesAndFailsFailClosed() {
        env.gateway.clearCandidates();
        PaymentIntent intent = env.createDefault();

        assertThat(intent.state()).isEqualTo(PaymentState.FAILED);
        assertThat(intent.failureReason()).contains("no_eligible_provider");
        assertThat(env.walletHolds.wasReleased(intent.internalId())).isTrue();
        assertThat(env.gateway.initiations()).isEmpty();
    }

    @Test
    void transientProviderUnavailabilityStaysPendingProvider() {
        env.gateway.unavailableNextInitiations(1);
        PaymentIntent intent = env.createDefault();

        assertThat(intent.state()).isEqualTo(PaymentState.PENDING_PROVIDER);
        assertThat(intent.providerRef()).isNull(); // initiate never succeeded
        assertThat(env.walletHolds.hasHold(intent.internalId())).isTrue(); // hold kept
        assertThat(env.lifecycle.startsOf(intent.id())).isEqualTo(1); // workflow retries
        assertThat(env.events.eventsOfType("payments.payment.failed.v1")).isEmpty();
    }

    @Test
    void distinctKeysAreFullyIsolated() {
        PaymentIntent a = env.create("key-a");
        env.events.reset();
        PaymentIntent b = env.create("key-b");

        assertThat(a).isNotEqualTo(b);
        assertThat(env.payments.count()).isEqualTo(2);
        assertThat(env.walletHolds.placedHolds()).hasSize(2);
        assertThat(env.gateway.initiations()).hasSize(2);

        // each key replays its own intent
        assertThat(env.createPayment.create("key-a", env.principals.principalId(), 150_000L,
                "KES", PaymentsTestEnv.WALLET, "honeycoin", Map.of(), null).intent().id())
                .isEqualTo(a.id());
        assertThat(env.createPayment.create("key-b", env.principals.principalId(), 150_000L,
                "KES", PaymentsTestEnv.WALLET, "honeycoin", Map.of(), null).intent().id())
                .isEqualTo(b.id());
        assertThat(env.payments.count()).isEqualTo(2);
    }

    @Test
    void metadataIsCarriedAndSurvivesReload() {
        PaymentIntent intent = env.createPayment.create("key-1", env.principals.principalId(),
                1_000L, "KES", PaymentsTestEnv.WALLET, "honeycoin",
                Map.of("order_id", "A-7731"), null).intent();
        assertThat(intent.metadata()).containsEntry("order_id", "A-7731");

        PaymentIntent reloaded = env.getPayment.get(intent.id());
        assertThat(reloaded.metadata()).containsEntry("order_id", "A-7731");

        PaymentIntent bare = env.create("key-2"); // absent metadata = empty map
        assertThat(bare.metadata()).isEmpty();
    }

    @Test
    void blankKeysAndBadAmountsAreValidationErrors() {
        assertThatThrownBy(() -> env.createPayment.create(" ", env.principals.principalId(),
                1_000L, "KES", PaymentsTestEnv.WALLET, null, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("Idempotency-Key");
        assertThatThrownBy(() -> env.createPayment.create(null, env.principals.principalId(),
                1_000L, "KES", PaymentsTestEnv.WALLET, null, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> env.createPayment.create("key-1", env.principals.principalId(),
                0L, "KES", PaymentsTestEnv.WALLET, null, Map.of(), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> env.createPayment.create("key-1", null, 1_000L, "KES",
                PaymentsTestEnv.WALLET, null, Map.of(), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void theFeeIsComputedFromTheRailScheduleAtCreation() {
        PaymentIntent mpesa = env.createPayment.create("key-1", env.principals.principalId(),
                10_000L, "KES", PaymentsTestEnv.WALLET, "mpesa", Map.of(), null).intent();
        assertThat(mpesa.fee().amountMinor()).isEqualTo(250L); // 250 bps of 10 000

        PaymentIntent small = env.createPayment.create("key-2", env.principals.principalId(),
                100L, "KES", PaymentsTestEnv.WALLET, "honeycoin", Map.of(), null).intent();
        assertThat(small.fee().amountMinor()).isEqualTo(100L); // 50 bps of 100 = 0.5 → 1, min 100
    }

    @Test
    void aReplayOfAMidPrefixOutageResumesTheSagaWithoutReRunningRisk() {
        // a first attempt with this key persisted the intent and claimed the
        // key, then the process died before the hold was placed — the intent
        // is stuck in CREATED (a state payments.yaml never documents as a
        // create response; nothing else repairs it: the workflow only starts
        // at the end of the prefix and expiry only fires from
        // PENDING_PROVIDER)
        UUID principal = env.principals.principalId();
        java.time.Duration ttl = java.time.Duration.ofSeconds(900);
        PaymentIntent stuck = com.sharkpay.payments.domain.PaymentIntent.newIntent(
                env.randomness.paymentId(), env.randomness.uuidV7(), principal, null,
                com.sharkpay.payments.domain.Destination.internalWallet(PaymentsTestEnv.WALLET),
                com.sharkpay.money.Money.of(150_000, "KES"),
                com.sharkpay.money.Money.of(750, "KES"), "key-resume",
                com.sharkpay.payments.domain.Rail.HONEYCOIN,
                PaymentsTestEnv.START.plus(ttl), java.util.Map.of(), PaymentsTestEnv.START);
        env.payments.save(stuck);
        env.idempotency.put(com.sharkpay.payments.ports.IdempotencyStore.Scope.CREATE_PAYMENT,
                "key-resume", new com.sharkpay.payments.ports.IdempotencyStore.StoredRequest(
                        CreatePaymentUseCase.fingerprint(principal, 150_000L, "KES",
                                PaymentsTestEnv.WALLET,
                                com.sharkpay.payments.domain.Rail.HONEYCOIN, ttl,
                                java.util.Map.of()), stuck.id()));

        // the caller retries the same request (same key + payload)
        var result = env.createPayment.create("key-resume", principal, 150_000L, "KES",
                PaymentsTestEnv.WALLET, "honeycoin", java.util.Map.of(), null);

        // the prefix re-drove idempotently to PENDING_PROVIDER...
        assertThat(result.replay()).isTrue();
        assertThat(result.intent().id()).isEqualTo(stuck.id());
        assertThat(result.intent().state()).isEqualTo(PaymentState.PENDING_PROVIDER);
        assertThat(result.intent().provider()).isEqualTo("honeycoin");
        // ...without re-running risk (the persisted CREATED row is the
        // evidence it already passed — a second evaluation would
        // double-count velocity on the risk side)
        assertThat(env.risk.evaluations()).isEmpty();
        // and with exactly one hold + one initiation for the logical request
        assertThat(env.payments.count()).isEqualTo(1);
        assertThat(env.walletHolds.placedHolds()).hasSize(1);
        assertThat(env.gateway.initiations()).hasSize(1);
        assertThat(env.ledger.totalEffects()).isEqualTo(1); // the single HOLD
        assertThat(env.lifecycle.startsOf(stuck.id())).isEqualTo(1);
    }

    @Test
    void metadataWithNullValuesOrBlankKeysIsAValidationError() {
        java.util.Map<String, String> nullValue = new java.util.HashMap<>();
        nullValue.put("order_id", null);
        assertThatThrownBy(() -> env.createPayment.create("key-1", env.principals.principalId(),
                1_000L, "KES", PaymentsTestEnv.WALLET, null, nullValue, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be a string");
        assertThat(env.payments.count()).isZero();

        java.util.Map<String, String> blankKey = new java.util.HashMap<>();
        blankKey.put("  ", "v");
        assertThatThrownBy(() -> env.createPayment.create("key-2", env.principals.principalId(),
                1_000L, "KES", PaymentsTestEnv.WALLET, null, blankKey, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-blank");
        assertThat(env.payments.count()).isZero();
    }

    @Test
    void aReplayWhoseIntentVanishedIsANotFoundNotASilentRetry() {
        // storage lost the row the idempotency key points at (corruption /
        // restore): the replay must surface 404, never mint a second intent
        PaymentIntent created = env.create("key-gone");
        env.payments.remove(created.id());

        assertThatThrownBy(() -> env.createPayment.create("key-gone",
                env.principals.principalId(), 150_000L, "KES", PaymentsTestEnv.WALLET,
                "honeycoin", Map.of(), null))
                .isInstanceOf(com.sharkpay.payments.domain.UnknownPaymentException.class)
                .hasMessageContaining(created.id());
        assertThat(env.payments.count()).isZero(); // no second intent minted
        // no second hold either — the original one is still recorded at the
        // wallet, nothing new was placed by the failed replay
        assertThat(env.walletHolds.placedHolds()).hasSize(1);
        assertThat(env.gateway.initiations()).hasSize(1);
    }
}
