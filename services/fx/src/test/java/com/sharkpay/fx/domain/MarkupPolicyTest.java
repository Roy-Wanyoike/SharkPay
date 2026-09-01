package com.sharkpay.fx.domain;

import com.sharkpay.money.Money;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MarkupPolicyTest {

    private static final Rate RAW_USD_KES = new Rate(129, 1, "USD", "KES");

    @Test
    void appliesMarkupWithExactIntegerMathAtVariousBps() {
        assertEquals(RAW_USD_KES, new MarkupPolicy(0).applyTo(RAW_USD_KES));
        // 129 × 9999/10000 (1 bp)
        assertEquals(new Rate(1289871, 10000, "USD", "KES"), new MarkupPolicy(1).applyTo(RAW_USD_KES));
        // 129 × 9850/10000 (150 bp = 1.5%) = 25413/200 in lowest terms
        assertEquals(new Rate(25413, 200, "USD", "KES"), new MarkupPolicy(150).applyTo(RAW_USD_KES));
        // 129 × 9500/10000 (500 bp) = 2451/20
        assertEquals(new Rate(2451, 20, "USD", "KES"), new MarkupPolicy(500).applyTo(RAW_USD_KES));
        // 129 × 1/10000 (9999 bp)
        assertEquals(new Rate(129, 10000, "USD", "KES"), new MarkupPolicy(9999).applyTo(RAW_USD_KES));
    }

    @Test
    void quotedRateNeverExceedsTheRawRateForTheCustomer() {
        Rate raw = new Rate(7719, 1_000_000, "KES", "USD");
        for (long bps = 0; bps <= 9999; bps += 377) {
            Rate quoted = new MarkupPolicy(bps).applyTo(raw);
            // exact rational comparison: quoted <= raw
            assertTrue(quoted.numerator() * raw.denominator() <= raw.numerator() * quoted.denominator(),
                    "quoted rate must not exceed raw at " + bps + " bps");
        }
    }

    @Test
    void rejectsInvalidBps() {
        assertThrows(FxDomainException.class, () -> new MarkupPolicy(-1));
        assertThrows(FxDomainException.class, () -> new MarkupPolicy(10000));
        assertThrows(FxDomainException.class, () -> new MarkupPolicy(Long.MIN_VALUE));
    }

    @Test
    void requiresARate() {
        assertThrows(NullPointerException.class, () -> new MarkupPolicy(150).applyTo(null));
        assertThrows(NullPointerException.class, () -> new MarkupPolicy(150).split(null));
    }

    @Test
    void splitsGrossExactlyViaLargestRemainder() {
        MarkupPolicy policy = new MarkupPolicy(150);
        Money gross = Money.of(12900, "KES");
        Money[] parts = policy.split(gross);
        // exact shares are 12706.5 and 193.5; the leftover minor unit goes to
        // the customer part (largest-remainder ties break to the lower index)
        assertEquals(Money.of(12707, "KES"), parts[0]);
        assertEquals(Money.of(193, "KES"), parts[1]);
        // never lost, never created
        assertEquals(gross, parts[0].add(parts[1]));
        // each part within one minor unit of its exact proportional share
        long exactCustomer = 12900L * 9850 / 10000;
        assertTrue(Math.abs(parts[0].amountMinor() - exactCustomer) <= 1);
    }

    @Test
    void splitHandlesExactAndZeroCases() {
        // exact shares 1270650 / 19350 — no remainder to distribute
        Money[] parts = new MarkupPolicy(150).split(Money.of(1290000, "KES"));
        assertEquals(Money.of(1270650, "KES"), parts[0]);
        assertEquals(Money.of(19350, "KES"), parts[1]);
        // zero markup: everything to the customer
        Money[] whole = new MarkupPolicy(0).split(Money.of(12900, "KES"));
        assertEquals(Money.of(12900, "KES"), whole[0]);
        assertEquals(Money.zero("KES"), whole[1]);
        // zero gross
        Money[] zeros = new MarkupPolicy(150).split(Money.zero("KES"));
        assertTrue(zeros[0].isZero());
        assertTrue(zeros[1].isZero());
    }
}
