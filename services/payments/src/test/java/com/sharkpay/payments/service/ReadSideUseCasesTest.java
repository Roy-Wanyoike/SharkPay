package com.sharkpay.payments.service;

import com.sharkpay.payments.domain.PaymentIntent;
import com.sharkpay.payments.domain.PaymentState;
import com.sharkpay.payments.ports.PaymentRepository.Page;
import com.sharkpay.payments.testsupport.PaymentsTestEnv;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Read side (payments.yaml listPayments / getPayment): filters, cursor
 * pagination, limit validation and the transition timeline.
 */
class ReadSideUseCasesTest {

    private final PaymentsTestEnv env = new PaymentsTestEnv();

    @Test
    void listsWithCursorPaginationAndNoCursorOnTheLastPage() {
        for (int i = 0; i < 3; i++) {
            env.create("k-" + i);
        }

        Page first = env.listPayments.list(null, null, null, null, 2, null);
        assertThat(first.items()).hasSize(2);
        assertThat(first.nextCursor()).isNotBlank();

        Page second = env.listPayments.list(null, null, null, null, 2, first.nextCursor());
        assertThat(second.items()).hasSize(1);
        assertThat(second.nextCursor()).isNull();
        assertThat(second.items().get(0).id())
                .isNotEqualTo(first.items().get(0).id())
                .isNotEqualTo(first.items().get(1).id());

        Page all = env.listPayments.list(null, null, null, null, 50, null);
        assertThat(all.items()).hasSize(3);
    }

    @Test
    void filtersByStatePrincipalAndCreatedRange() {
        UUID owner = UUID.randomUUID();
        PaymentIntent a = env.createPayment.create("k-a", owner, 1_000L, "KES",
                PaymentsTestEnv.WALLET, "honeycoin", java.util.Map.of(), null).intent();
        PaymentIntent b = env.createPayment.create("k-b", owner, 1_000L, "KES",
                PaymentsTestEnv.WALLET, "honeycoin", java.util.Map.of(), null).intent();
        env.recordResult.record(null, b.id(), "SUCCEEDED");
        PaymentIntent other = env.create("k-other");

        assertThat(env.listPayments.list(null, owner, null, null, 50, null).items())
                .extracting(PaymentIntent::id).containsExactlyInAnyOrder(a.id(), b.id());
        assertThat(env.listPayments.list("SUCCEEDED", null, null, null, 50, null).items())
                .extracting(PaymentIntent::id).containsExactly(b.id());
        assertThat(env.listPayments.list("PENDING_PROVIDER", null, null, null, 50, null).items())
                .extracting(PaymentIntent::id).containsExactly(a.id(), other.id());
        assertThat(env.listPayments.list(null, null,
                Instant.parse("2026-09-01T10:00:01Z"), null, 50, null).items()).isEmpty();
        assertThat(env.listPayments.list(null, null, null,
                Instant.parse("2026-09-01T10:00:00Z"), 50, null).items()).isEmpty();
        assertThat(env.listPayments.list(null, null, null,
                Instant.parse("2026-09-01T23:59:59Z"), 50, null).items()).hasSize(3);
    }

    @Test
    void limitDefaultsValidateAndUnknownStatesAreRejected() {
        assertThat(env.listPayments.list(null, null, null, null, null, null).items()).isEmpty();

        assertThatThrownBy(() -> env.listPayments.list(null, null, null, null, 0, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("[1, 100]");
        assertThatThrownBy(() -> env.listPayments.list(null, null, null, null, 101, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> env.listPayments.list("bogus", null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unknown state");
        assertThatThrownBy(() -> env.listPayments.list(null, null, null, null, null, " "))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("cursor");
    }

    @Test
    void timelineReturnsTheAppendOnlyHistoryInOrder() {
        PaymentIntent intent = env.createDefault();
        env.recordResult.record(null, intent.id(), "SUCCEEDED");

        var timeline = env.listPayments.timeline(intent.id());
        assertThat(timeline).extracting(row -> row.to())
                .containsExactly(PaymentState.CREATED, PaymentState.PENDING_PROVIDER,
                        PaymentState.PROCESSING, PaymentState.SUCCEEDED);
        assertThat(timeline.get(3).entryId()).isEqualTo(intent.captureEntryId());
        assertThat(timeline).allSatisfy(row -> assertThat(row.seq()).isPositive());

        assertThatThrownBy(() -> env.listPayments.timeline(null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void getPaymentReadsAnd404s() {
        PaymentIntent intent = env.createDefault();
        assertThat(env.getPayment.get(intent.id()).id()).isEqualTo(intent.id());
        assertThatThrownBy(() -> env.getPayment.get("pay_0123456789abcdef0123456789abcdee"))
                .isInstanceOf(com.sharkpay.payments.domain.UnknownPaymentException.class);
        assertThatThrownBy(() -> env.getPayment.get(null))
                .isInstanceOf(NullPointerException.class);
    }
}
