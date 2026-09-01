package com.sharkpay.payments.domain;

import com.sharkpay.money.CurrencyMismatchException;
import com.sharkpay.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The PaymentIntent aggregate: the happy lifecycle walk, every guarded
 * transition, invariants (positive amount, fee currency match, expiry window,
 * capturable amount) and the pending-transition log the repository drains.
 */
class PaymentIntentTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:00Z");
    private static final Instant T1 = T0.plusSeconds(60);
    private static final UUID HOLD_ENTRY = UUID.randomUUID();
    private static final UUID CAPTURE_ENTRY = UUID.randomUUID();
    private static final UUID RELEASE_ENTRY = UUID.randomUUID();
    private static final UUID REVERSAL_ENTRY = UUID.randomUUID();
    private static final Money AMOUNT = Money.of(150_000, "KES");
    private static final Money FEE = Money.of(750, "KES");
    private static final String WALLET = "wal_0123456789abcdef0123456789abcdef";

    private static PaymentIntent newIntent() {
        return PaymentIntent.newIntent("pay_0123456789abcdef0123456789abcdef",
                UUID.randomUUID(), UUID.randomUUID(), null,
                Destination.internalWallet("wal_0123456789abcdef0123456789abcdef"),
                AMOUNT, FEE, "key-1", Rail.HONEYCOIN, T0.plusSeconds(900), Map.of(), T0);
    }

    @Test
    void newIntentStartsInCreatedWithTheCreationTransitionRow() {
        PaymentIntent intent = newIntent();
        assertThat(intent.state()).isEqualTo(PaymentState.CREATED);
        assertThat(intent.transitionSeq()).isEqualTo(1);
        assertThat(intent.idempotencyKey()).isEqualTo("key-1");
        var drained = intent.drainPendingTransitions();
        assertThat(drained).hasSize(1);
        StateTransition created = drained.get(0);
        assertThat(created.from()).isNull();
        assertThat(created.to()).isEqualTo(PaymentState.CREATED);
        assertThat(created.seq()).isEqualTo(1);
        assertThat(created.reason()).contains("rail=honeycoin");
    }

    @Test
    void walksTheHappyLifecycleToReversed() {
        PaymentIntent intent = newIntent();
        intent.markPendingProvider("hld_1", HOLD_ENTRY, T0);
        assertThat(intent.state()).isEqualTo(PaymentState.PENDING_PROVIDER);
        assertThat(intent.holdId()).isEqualTo("hld_1");
        assertThat(intent.holdEntryId()).isEqualTo(HOLD_ENTRY);

        intent.markProcessing(T0);
        assertThat(intent.state()).isEqualTo(PaymentState.PROCESSING);

        intent.markSucceeded(CAPTURE_ENTRY, T0);
        assertThat(intent.state()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(intent.captureEntryId()).isEqualTo(CAPTURE_ENTRY);
        assertThat(intent.capturableAmount()).isEqualTo(AMOUNT);

        intent.markReversed("provider reversal", REVERSAL_ENTRY, AMOUNT, T0);
        assertThat(intent.state()).isEqualTo(PaymentState.REVERSED);
        assertThat(intent.reversalEntryId()).isEqualTo(REVERSAL_ENTRY);
        assertThat(intent.reversedAmount()).isEqualTo(AMOUNT);
        assertThat(intent.capturableAmount()).isEqualTo(Money.zero("KES"));

        // 5 rows: creation + PENDING_PROVIDER + PROCESSING + SUCCEEDED + REVERSED
        assertThat(intent.drainPendingTransitions()).hasSize(5);
        assertThat(intent.transitionSeq()).isEqualTo(5);
    }

    @Test
    void riskDenyLandsInBlockedWithoutMoneyMoving() {
        PaymentIntent intent = newIntent();
        intent.markBlocked("risk_deny: velocity", T1);
        assertThat(intent.state()).isEqualTo(PaymentState.BLOCKED);
        assertThat(intent.holdId()).isNull();
        assertThat(intent.holdEntryId()).isNull();
    }

    @Test
    void userCancelWorksFromCreatedAndPendingProvider() {
        newIntent().markCancelled(null, T1); // no hold yet — nothing to release

        PaymentIntent held = newIntent();
        held.markPendingProvider("hld_1", HOLD_ENTRY, T0);
        held.markCancelled(RELEASE_ENTRY, T1);
        assertThat(held.state()).isEqualTo(PaymentState.CANCELLED);
        assertThat(held.releaseEntryId()).isEqualTo(RELEASE_ENTRY);
    }

    @Test
    void expiryAndFailureReleaseEntryIsKeptNotOverwritten() {
        PaymentIntent intent = newIntent();
        intent.markPendingProvider("hld_1", HOLD_ENTRY, T0);
        intent.markExpired(RELEASE_ENTRY, T1);
        assertThat(intent.state()).isEqualTo(PaymentState.EXPIRED);
        assertThat(intent.releaseEntryId()).isEqualTo(RELEASE_ENTRY);

        PaymentIntent failed = newIntent();
        failed.markPendingProvider("hld_1", HOLD_ENTRY, T0);
        failed.markFailed("provider_rejected", RELEASE_ENTRY, T1);
        assertThat(failed.state()).isEqualTo(PaymentState.FAILED);
        assertThat(failed.failureReason()).isEqualTo("provider_rejected");
        assertThat(failed.releaseEntryId()).isEqualTo(RELEASE_ENTRY);
    }

    @Test
    void expiryOnlyFromPendingProvider() {
        PaymentIntent processing = newIntent();
        processing.markPendingProvider("hld_1", HOLD_ENTRY, T0);
        processing.markProcessing(T0);
        assertThatThrownBy(() -> processing.markExpired(null, T1))
                .isInstanceOf(PaymentStateException.class)
                .hasMessageContaining("PROCESSING")
                .hasMessageContaining("EXPIRED");
    }

    @Test
    void failedFromPendingProviderAndFromProcessing() {
        PaymentIntent fromPending = newIntent();
        fromPending.markPendingProvider("hld_1", HOLD_ENTRY, T0);
        fromPending.markFailed("provider_failed", RELEASE_ENTRY, T1);

        PaymentIntent fromProcessing = newIntent();
        fromProcessing.markPendingProvider("hld_1", HOLD_ENTRY, T0);
        fromProcessing.markProcessing(T0);
        fromProcessing.markFailed("rail_failure", RELEASE_ENTRY, T1);
        assertThat(fromProcessing.state()).isEqualTo(PaymentState.FAILED);
    }

    @Test
    void reversedAllowedFromFailedToo() {
        PaymentIntent intent = newIntent();
        intent.markPendingProvider("hld_1", HOLD_ENTRY, T0);
        intent.markFailed("provider_failed", RELEASE_ENTRY, T1);
        intent.markReversed("late funds recovered", REVERSAL_ENTRY, AMOUNT, T1);
        assertThat(intent.state()).isEqualTo(PaymentState.REVERSED);
    }

    @Test
    void illegalTransitionsThrowWithPaymentContext() {
        PaymentIntent intent = newIntent();
        assertThatThrownBy(() -> intent.markSucceeded(CAPTURE_ENTRY, T1))
                .isInstanceOf(PaymentStateException.class)
                .hasMessageContaining(intent.id())
                .hasMessageContaining("CREATED")
                .hasMessageContaining("SUCCEEDED");
        assertThatThrownBy(() -> newIntent().markProcessing(T1))
                .isInstanceOf(PaymentStateException.class);
        assertThatThrownBy(() -> newIntent().markReversed("r", REVERSAL_ENTRY, AMOUNT, T1))
                .isInstanceOf(PaymentStateException.class);
    }

    @Test
    void moneyCarryingTransitionsRequireTheirRefs() {
        PaymentIntent intent = newIntent();
        assertThatThrownBy(() -> intent.markPendingProvider(null, HOLD_ENTRY, T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("holdId");
        assertThatThrownBy(() -> intent.markPendingProvider("  ", HOLD_ENTRY, T1))
                .isInstanceOf(IllegalArgumentException.class);
        intent.markPendingProvider("hld_1", HOLD_ENTRY, T0);
        intent.markProcessing(T0);
        assertThatThrownBy(() -> intent.markSucceeded(null, T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("captureEntryId");
        intent.markSucceeded(CAPTURE_ENTRY, T0);
        assertThatThrownBy(() -> intent.markReversed("r", null, AMOUNT, T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("reversalEntryId");
    }

    @Test
    void reversalAmountMustBeNonNegativeAndSameCurrency() {
        PaymentIntent intent = succeeded();
        assertThatThrownBy(() -> intent.markReversed("r", REVERSAL_ENTRY,
                Money.of(-1, "KES"), T1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        assertThatThrownBy(() -> intent.markReversed("r", REVERSAL_ENTRY,
                Money.of(1, "USD"), T1))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void isExpiredAtOnlyWhilePendingProviderAndPastDeadline() {
        PaymentIntent intent = newIntent();
        assertThat(intent.isExpiredAt(T0.plusSeconds(899))).isFalse();
        assertThat(intent.isExpiredAt(T0.plusSeconds(900))).isFalse(); // CREATED: never expires
        intent.markPendingProvider("hld_1", HOLD_ENTRY, T0);
        assertThat(intent.isExpiredAt(T0.plusSeconds(899))).isFalse();
        assertThat(intent.isExpiredAt(T0.plusSeconds(900))).isTrue();
        intent.markProcessing(T0);
        assertThat(intent.isExpiredAt(T0.plusSeconds(10_000))).isFalse();
    }

    @Test
    void recordProviderHandoffKeepsStateButRecordsRefs() {
        PaymentIntent intent = newIntent();
        intent.markPendingProvider("hld_1", HOLD_ENTRY, T0);
        intent.drainPendingTransitions(); // repository already saved these
        intent.recordProviderHandoff("honeycoin", "hc_1", T1);
        assertThat(intent.state()).isEqualTo(PaymentState.PENDING_PROVIDER);
        assertThat(intent.provider()).isEqualTo("honeycoin");
        assertThat(intent.providerRef()).isEqualTo("hc_1");
        assertThat(intent.updatedAt()).isEqualTo(T1);
        assertThat(intent.drainPendingTransitions()).isEmpty(); // no state row
    }

    @Test
    void constructionValidatesIdsAmountsFeeAndExpiry() {
        assertThatThrownBy(() -> PaymentIntent.newIntent("pay_short", UUID.randomUUID(),
                UUID.randomUUID(), null, Destination.internalWallet(WALLET), AMOUNT, FEE, "k",
                Rail.HONEYCOIN, T0.plusSeconds(900), Map.of(), T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payment id must match");
        assertThatThrownBy(() -> PaymentIntent.newIntent("pay_0123456789abcdef0123456789abcdef",
                UUID.randomUUID(), UUID.randomUUID(), null,
                Destination.internalWallet(WALLET), Money.zero("KES"), FEE, "k",
                Rail.HONEYCOIN, T0.plusSeconds(900), Map.of(), T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
        assertThatThrownBy(() -> PaymentIntent.newIntent("pay_0123456789abcdef0123456789abcdef",
                UUID.randomUUID(), UUID.randomUUID(), null,
                Destination.internalWallet(WALLET), AMOUNT, Money.of(-1, "KES"), "k",
                Rail.HONEYCOIN, T0.plusSeconds(900), Map.of(), T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        assertThatThrownBy(() -> PaymentIntent.newIntent("pay_0123456789abcdef0123456789abcdef",
                UUID.randomUUID(), UUID.randomUUID(), null,
                Destination.internalWallet(WALLET), AMOUNT, Money.of(1, "USD"), "k",
                Rail.HONEYCOIN, T0.plusSeconds(900), Map.of(), T0))
                .isInstanceOf(CurrencyMismatchException.class);
        assertThatThrownBy(() -> PaymentIntent.newIntent("pay_0123456789abcdef0123456789abcdef",
                UUID.randomUUID(), UUID.randomUUID(), null,
                Destination.internalWallet(WALLET), AMOUNT, FEE, "  ",
                Rail.HONEYCOIN, T0.plusSeconds(900), Map.of(), T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("idempotencyKey");
        assertThatThrownBy(() -> PaymentIntent.newIntent("pay_0123456789abcdef0123456789abcdef",
                UUID.randomUUID(), UUID.randomUUID(), null,
                Destination.internalWallet(WALLET), AMOUNT, FEE, "k",
                Rail.HONEYCOIN, T0, Map.of(), T0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("expiresAt must be after createdAt");
    }

    @Test
    void rehydrateRestoresTheFullAggregateAndMetadataIsIsolated() {
        java.util.Map<String, String> source = new java.util.HashMap<>();
        source.put("order", "A-7731");
        PaymentIntent intent = PaymentIntent.rehydrate("pay_0123456789abcdef0123456789abcdef",
                UUID.randomUUID(), UUID.randomUUID(), null,
                Destination.internalWallet(WALLET), AMOUNT, FEE, Rail.HONEYCOIN,
                PaymentState.SUCCEEDED, "k", T0.plusSeconds(900), source,
                "honeycoin", "hc_1", "hld_1", HOLD_ENTRY, CAPTURE_ENTRY, RELEASE_ENTRY,
                REVERSAL_ENTRY, AMOUNT, "done", T0, T1, 7);
        assertThat(intent.state()).isEqualTo(PaymentState.SUCCEEDED);
        assertThat(intent.metadata()).containsEntry("order", "A-7731");
        // mutating the source after construction cannot reach the aggregate
        source.put("order", "tampered");
        assertThat(intent.metadata()).containsEntry("order", "A-7731");
        assertThat(intent.drainPendingTransitions()).isEmpty(); // rehydrated: nothing pending
    }

    @Test
    void rehydrateNormalizesBlankSourceWalletsAndNullMetadata() {
        PaymentIntent blankSource = PaymentIntent.rehydrate(
                "pay_0123456789abcdef0123456789abcdef", UUID.randomUUID(), UUID.randomUUID(),
                "   ", Destination.internalWallet(WALLET), AMOUNT, FEE, Rail.HONEYCOIN,
                PaymentState.CREATED, "k", T0.plusSeconds(900), null, null, null, null, null,
                null, null, null, null, null, T0, T0, 0);
        assertThat(blankSource.sourceWalletId()).isNull(); // blank never persists as ""
        assertThat(blankSource.metadata()).isEmpty();     // null metadata = empty map

        PaymentIntent trimmed = PaymentIntent.rehydrate(
                "pay_0123456789abcdef0123456789abcdef", UUID.randomUUID(), UUID.randomUUID(),
                " wal_x ", Destination.internalWallet(WALLET), AMOUNT, FEE, Rail.HONEYCOIN,
                PaymentState.CREATED, "k", T0.plusSeconds(900), null, null, null, null, null,
                null, null, null, null, null, T0, T0, 0);
        assertThat(trimmed.sourceWalletId()).isEqualTo("wal_x"); // trimmed, not blanked
    }

    @Test
    void equalsAndHashCodeFollowTheIdOnly() {
        PaymentIntent a = newIntent();
        PaymentIntent b = newIntent();
        assertThat(a).isEqualTo(b);
        assertThat(a).hasSameHashCodeAs(b);
        assertThat(a).isNotSameAs(b);
        assertThat(a.toString()).contains(a.id()).contains("CREATED").contains("honeycoin");
        // identity short-circuit and non-intent comparisons are plain false;
        // intents sharing the id stay equal (succeeded() shares the fixture id)
        assertThat(a).isEqualTo(a);
        assertThat(a).isNotEqualTo(null);
        assertThat(a).isNotEqualTo("pay_0123456789abcdef0123456789abcdef");
        assertThat(a).isEqualTo(succeeded());
        assertThat(a).hasSameHashCodeAs(succeeded());
    }

    private static PaymentIntent succeeded() {
        PaymentIntent intent = newIntent();
        intent.markPendingProvider("hld_1", HOLD_ENTRY, T0);
        intent.markProcessing(T0);
        intent.markSucceeded(CAPTURE_ENTRY, T0);
        return intent;
    }
}
