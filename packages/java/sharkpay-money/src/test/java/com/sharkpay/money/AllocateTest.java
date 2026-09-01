package com.sharkpay.money;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllocateTest {

    private static long sumMinor(Money[] parts) {
        long total = 0L;
        for (Money p : parts) {
            total = Math.addExact(total, p.amountMinor());
        }
        return total;
    }

    @Test
    void thirdsOfOneHundred() {
        Money[] parts = Money.of(100L, "KES").allocate(new int[]{1, 1, 1}, 3);
        assertEquals(34L, parts[0].amountMinor());
        assertEquals(33L, parts[1].amountMinor());
        assertEquals(33L, parts[2].amountMinor());
        assertEquals(100L, sumMinor(parts));
    }

    @Test
    void halvesOfOneHundredOne() {
        Money[] parts = Money.of(101L, "KES").allocate(new int[]{1, 1}, 2);
        assertEquals(51L, parts[0].amountMinor());
        assertEquals(50L, parts[1].amountMinor());
    }

    @Test
    void percentageSplit() {
        Money[] parts = Money.of(999L, "KES").allocate(new int[]{50, 50}, 100);
        assertEquals(500L, parts[0].amountMinor());
        assertEquals(499L, parts[1].amountMinor());
    }

    @Test
    void twoToOneRatio() {
        Money[] parts = Money.of(90L, "KES").allocate(new int[]{2, 1}, 3);
        assertEquals(60L, parts[0].amountMinor());
        assertEquals(30L, parts[1].amountMinor());
    }

    @Test
    void zeroRatioYieldsZeroPart() {
        Money[] parts = Money.of(5L, "KES").allocate(new int[]{0, 1}, 1);
        assertEquals(0L, parts[0].amountMinor());
        assertEquals(5L, parts[1].amountMinor());
    }

    @Test
    void tieBreaksTowardLowerIndex() {
        // 1 minor unit, two equal halves: the leftover unit goes to index 0.
        Money[] parts = Money.of(1L, "KES").allocate(new int[]{1, 1}, 2);
        assertEquals(1L, parts[0].amountMinor());
        assertEquals(0L, parts[1].amountMinor());
    }

    @Test
    void singleRatioTakesEverything() {
        Money[] parts = Money.of(12345L, "KES").allocate(new int[]{1}, 1);
        assertEquals(1, parts.length);
        assertEquals(12345L, parts[0].amountMinor());
    }

    @Test
    void exactSumHoldsAcrossSplits() {
        int[][] splits = {
            new int[]{1, 1, 1}, new int[]{1, 2}, new int[]{7, 3},
            new int[]{1, 0, 0}, new int[]{37, 41, 22},
            new int[]{1, 1, 1, 1, 1}, new int[]{99, 1}
        };
        for (int[] ratios : splits) {
            int total = 0;
            for (int r : ratios) {
                total += r;
            }
            for (long amount : new long[]{0L, 1L, 2L, 97L, 1000L, 999_999L}) {
                Money m = Money.of(amount, "KES");
                Money[] parts = m.allocate(ratios, total);
                assertEquals(amount, sumMinor(parts),
                    "exact sum broken for " + amount + " split " + java.util.Arrays.toString(ratios));
                for (Money p : parts) {
                    assertEquals("KES", p.currency());
                }
            }
        }
    }

    @Test
    void stablecoinSplitsPreserveMicroUnits() {
        Money[] parts = Money.of(10L, "USDC").allocate(new int[]{1, 1, 1}, 3);
        assertEquals(10L, sumMinor(parts));
        assertEquals(4L, parts[0].amountMinor());
        assertEquals(3L, parts[1].amountMinor());
        assertEquals(3L, parts[2].amountMinor());
    }

    @Test
    void negativeAmountAllocatesOnMagnitude() {
        Money[] parts = Money.of(-100L, "KES").allocate(new int[]{1, 1, 1}, 3);
        assertEquals(-34L, parts[0].amountMinor());
        assertEquals(-33L, parts[1].amountMinor());
        assertEquals(-33L, parts[2].amountMinor());
        assertEquals(-100L, sumMinor(parts));
    }

    @Test
    void negativeTieBreaksTowardLowerIndex() {
        Money[] parts = Money.of(-1L, "KES").allocate(new int[]{1, 1}, 2);
        assertEquals(-1L, parts[0].amountMinor());
        assertEquals(0L, parts[1].amountMinor());
    }

    @Test
    void minInt64AllocatesEntireMagnitude() {
        Money[] parts = Money.of(Long.MIN_VALUE, "KES").allocate(new int[]{1}, 1);
        assertEquals(Long.MIN_VALUE, parts[0].amountMinor());

        // Half of MIN_VALUE magnitude rounds to exactly -2^62 each.
        Money[] halves = Money.of(Long.MIN_VALUE, "KES").allocate(new int[]{1, 1}, 2);
        assertEquals(Long.MIN_VALUE, Math.addExact(halves[0].amountMinor(), halves[1].amountMinor()));
    }

    @Test
    void largerRatioNeverYieldsSmallerPartForNonNegativeAmounts() {
        Money[] parts = Money.of(7L, "KES").allocate(new int[]{3, 1}, 4);
        assertTrue(parts[0].amountMinor() >= parts[1].amountMinor());
        parts = Money.of(11L, "KES").allocate(new int[]{10, 9}, 19);
        assertTrue(parts[0].amountMinor() >= parts[1].amountMinor());
    }

    @Test
    void allocationIsDeterministic() {
        Money m = Money.of(12345L, "KES");
        int[] ratios = new int[]{7, 11, 13};
        Money[] first = m.allocate(ratios, 31);
        for (int i = 0; i < 20; i++) {
            Money[] again = m.allocate(ratios, 31);
            for (int p = 0; p < first.length; p++) {
                assertEquals(first[p], again[p]);
            }
        }
    }

    @Test
    void rejectsInvalidInputs() {
        Money m = Money.of(100L, "KES");
        assertThrows(InvalidRatiosException.class, () -> m.allocate(new int[]{}, 1));
        assertThrows(InvalidRatiosException.class, () -> m.allocate(new int[]{1, 1}, 0));
        assertThrows(InvalidRatiosException.class, () -> m.allocate(new int[]{1, 1}, -3));
        assertThrows(InvalidRatiosException.class, () -> m.allocate(new int[]{-1, 2}, 1));
        assertThrows(InvalidRatiosException.class, () -> m.allocate(new int[]{1, 2}, 4));
        assertThrows(NullPointerException.class, () -> m.allocate(null, 1));
    }
}
