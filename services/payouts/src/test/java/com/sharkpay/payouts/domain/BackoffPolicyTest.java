package com.sharkpay.payouts.domain;

import com.sharkpay.payouts.fakes.ScriptedRandomness;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * BackoffPolicy: bounded exponential backoff with jitter for provider
 * submission retries. The javadoc contract is normative for money safety
 * (retry storms are an outage vector):
 * <ul>
 *   <li><b>bounded</b> — the exponential component never exceeds {@code cap}
 *       and never goes negative, for ANY attempt count;</li>
 *   <li><b>monotonic</b> — non-decreasing in the attempt number (zero
 *       jitter);</li>
 *   <li><b>jitter</b> — the draw is uniform in [0, jitterBound] on top of the
 *       schedule, and jitterBound ≤ cap/8 is enforced at construction.</li>
 * </ul>
 */
class BackoffPolicyTest {

    @Test
    void theDeterministicScheduleIsExactForSmallAttemptCounts() {
        BackoffPolicy policy = new BackoffPolicy(Duration.ofMillis(1_000),
                Duration.ofMillis(300_000), Duration.ZERO);
        assertThat(policy.cappedExponentialMs(0)).isEqualTo(1_000L);
        assertThat(policy.cappedExponentialMs(1)).isEqualTo(2_000L);
        assertThat(policy.cappedExponentialMs(2)).isEqualTo(4_000L);
        assertThat(policy.cappedExponentialMs(3)).isEqualTo(8_000L);
        assertThat(policy.cappedExponentialMs(8)).isEqualTo(256_000L);
        assertThat(policy.cappedExponentialMs(9)).isEqualTo(300_000L); // capped
        assertThat(policy.cappedExponentialMs(10)).isEqualTo(300_000L);
    }

    @Test
    void theScheduleIsMonotonicAndNeverExceedsTheCapForEveryAttemptCount() {
        BackoffPolicy policy = new BackoffPolicy(Duration.ofMillis(1_000),
                Duration.ofMillis(300_000), Duration.ZERO);
        long previous = 0;
        for (int attempts = 0; attempts <= 200; attempts++) {
            long delay = policy.cappedExponentialMs(attempts);
            assertThat(delay)
                    .as("delay at attempts=%d", attempts)
                    .isGreaterThanOrEqualTo(0)     // a negative delay is an immediate-retry storm
                    .isLessThanOrEqualTo(300_000L) // never exceeds the cap
                    .isGreaterThanOrEqualTo(previous); // monotonic
            previous = delay;
        }
    }

    @Test
    void attemptCountsPastTheShiftGuardSaturateAtTheCap() {
        BackoffPolicy policy = new BackoffPolicy(Duration.ofMillis(1_000),
                Duration.ofMillis(300_000), Duration.ZERO);
        for (int attempts : new int[]{62, 63, 64, 100, 200}) {
            assertThat(policy.cappedExponentialMs(attempts))
                    .as("attempts=%d", attempts)
                    .isEqualTo(300_000L);
        }
    }

    @Test
    void theScheduleSaturatesWhenBaseWouldOverflowBeforeTheCapIsReached() {
        // base 1s << 54 wraps long — the schedule must return the cap, never
        // a wrapped (possibly negative or tiny) value
        BackoffPolicy policy = new BackoffPolicy(Duration.ofMillis(1_000),
                Duration.ofMillis(300_000), Duration.ZERO);
        for (int attempts = 40; attempts <= 61; attempts++) {
            assertThat(policy.cappedExponentialMs(attempts))
                    .as("attempts=%d", attempts)
                    .isEqualTo(300_000L);
        }
    }

    @Test
    void aCapBelowTheDoubledBaseCappedImmediately() {
        BackoffPolicy tight = new BackoffPolicy(Duration.ofSeconds(60), Duration.ofSeconds(60),
                Duration.ZERO);
        assertThat(tight.cappedExponentialMs(0)).isEqualTo(60_000L);
        assertThat(tight.cappedExponentialMs(1)).isEqualTo(60_000L);
    }

    @Test
    void aTinyBaseUnderAHugeCapStaysExactUntilTheShiftGuard() {
        BackoffPolicy policy = new BackoffPolicy(Duration.ofMillis(2),
                Duration.ofSeconds(300), Duration.ZERO);
        assertThat(policy.cappedExponentialMs(10)).isEqualTo(2_048L);
        assertThat(policy.cappedExponentialMs(61)).isEqualTo(300_000L);
    }

    @Test
    void negativeAttemptCountsAreRejected() {
        BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(10),
                Duration.ZERO);
        assertThatThrownBy(() -> policy.cappedExponentialMs(-1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempts must be non-negative");
        assertThatThrownBy(() -> policy.nextBackoff(-1, new ScriptedRandomness()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("attempts must be non-negative");
    }

    @Test
    void nextBackoffAddsNoJitterWhenTheBoundIsZero() {
        BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(300),
                null); // null jitter = zero
        ScriptedRandomness randomness = new ScriptedRandomness();
        assertThat(policy.nextBackoff(3, randomness)).isEqualTo(Duration.ofSeconds(8));
        assertThat(randomness.draws()).isZero(); // the port is never drawn
    }

    @Test
    void nextBackoffDrawsUniformJitterWithinTheBound() {
        BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(300),
                Duration.ofMillis(250));
        ScriptedRandomness jitter = new ScriptedRandomness().script(0, 125, 250, 999);
        assertThat(policy.nextBackoff(0, jitter)).isEqualTo(Duration.ofMillis(1_000));
        assertThat(policy.nextBackoff(0, jitter)).isEqualTo(Duration.ofMillis(1_125));
        assertThat(policy.nextBackoff(0, jitter)).isEqualTo(Duration.ofMillis(1_250));
        // values outside [0, 250] fail loudly — the scripted fake is honest
        assertThatThrownBy(() -> policy.nextBackoff(0, jitter))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside the requested bound");
    }

    @Test
    void theJitterSitsOnTopOfTheCappedSchedule() {
        BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(3),
                Duration.ofMillis(250));
        ScriptedRandomness jitter = new ScriptedRandomness().script(250);
        // schedule is already at the cap (1s << 2 = 4s > 3s), jitter still adds
        assertThat(policy.nextBackoff(2, jitter)).isEqualTo(Duration.ofMillis(3_250));
    }

    @Test
    void constructionValidatesBaseCapAndJitter() {
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ZERO, Duration.ofSeconds(1), null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base must be positive");
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ofSeconds(-1), Duration.ofSeconds(1),
                null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ofSeconds(10), Duration.ofSeconds(9),
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("cap must be >= base");
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(10),
                Duration.ofMillis(-1)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("jitter bound must be non-negative");
        // jitter must stay within cap/8
        assertThatThrownBy(() -> new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(8),
                Duration.ofMillis(1_001)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at most cap/8");
        // exactly cap/8 is the documented ceiling
        BackoffPolicy legal = new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(8),
                Duration.ofSeconds(1));
        assertThat(legal.jitterBound()).isEqualTo(Duration.ofSeconds(1));
        assertThatThrownBy(() -> new BackoffPolicy(null, Duration.ofSeconds(1), null))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void accessorsExposeTheConfiguration() {
        BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(1), Duration.ofMinutes(5),
                Duration.ofMillis(250));
        assertThat(policy.base()).isEqualTo(Duration.ofSeconds(1));
        assertThat(policy.cap()).isEqualTo(Duration.ofMinutes(5));
        assertThat(policy.jitterBound()).isEqualTo(Duration.ofMillis(250));
    }

    @Test
    void nextBackoffRequiresTheRandomnessPort() {
        BackoffPolicy policy = new BackoffPolicy(Duration.ofSeconds(1), Duration.ofSeconds(10),
                Duration.ofMillis(100));
        assertThatThrownBy(() -> policy.nextBackoff(0, null))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("randomness is required");
    }
}
