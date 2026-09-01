package com.sharkpay.identity.fakes;

import com.sharkpay.identity.ports.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;

/**
 * Deterministic clock: fixed instant, manually advanceable.
 */
public final class FixedClock implements Clock {

    private volatile OffsetDateTime now;

    public FixedClock(OffsetDateTime initial) {
        this.now = initial;
    }

    @Override
    public OffsetDateTime now() {
        return now;
    }

    public void advanceBy(Duration duration) {
        now = now.plus(duration);
    }

    public void set(OffsetDateTime at) {
        now = at;
    }
}
