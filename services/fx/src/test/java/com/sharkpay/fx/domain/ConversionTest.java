package com.sharkpay.fx.domain;

import com.sharkpay.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConversionTest {

    private static final Instant NOW = Instant.parse("2026-09-01T10:00:30Z");
    private static final Money SOURCE = Money.of(10000, "USD");
    private static final Money TARGET = Money.of(1270650, "KES");
    private static final Rate RATE = new Rate(25413, 200, "USD", "KES");

    private Conversion conversion() {
        return new Conversion("cnv_" + "1".repeat(26), "fxq_" + "0".repeat(26),
                "wallet:usr_42:USD", "wallet:usr_42:KES",
                SOURCE, TARGET, RATE, "fx:cnv_123", "0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d",
                ConversionState.EXECUTED, NOW);
    }

    @Test
    void buildsTheFourLegJournalEntry() {
        List<Leg> legs = conversion().legs();
        assertEquals(4, legs.size());
        // (1) DEBIT customer base-currency wallet
        assertEquals(new Leg("wallet:usr_42:USD", "USD", 10000, Direction.DEBIT), legs.get(0));
        // (2) CREDIT FX position for the source currency
        assertEquals(new Leg("fx-position:USD", "USD", 10000, Direction.CREDIT), legs.get(1));
        // (3) DEBIT FX position for the target currency
        assertEquals(new Leg("fx-position:KES", "KES", 1270650, Direction.DEBIT), legs.get(2));
        // (4) CREDIT customer quote-currency wallet
        assertEquals(new Leg("wallet:usr_42:KES", "KES", 1270650, Direction.CREDIT), legs.get(3));
    }

    @Test
    void legsBalancePerCurrency() {
        List<Leg> legs = conversion().legs();
        long sourceDebits = sum(legs, "USD", Direction.DEBIT);
        long sourceCredits = sum(legs, "USD", Direction.CREDIT);
        long targetDebits = sum(legs, "KES", Direction.DEBIT);
        long targetCredits = sum(legs, "KES", Direction.CREDIT);
        assertEquals(sourceDebits, sourceCredits, "legs 1+2 must balance in the source currency");
        assertEquals(targetDebits, targetCredits, "legs 3+4 must balance in the target currency");
        assertEquals(10000, sourceDebits);
        assertEquals(1270650, targetDebits);
    }

    @Test
    void fxPositionAccountRefsFollowTheConvention() {
        assertEquals("fx-position:USD", AccountRefs.fxPosition("USD"));
        assertEquals("fx-position:KES", AccountRefs.fxPosition("KES"));
        assertEquals("fx-position:", AccountRefs.FX_POSITION_PREFIX);
        assertThrows(FxDomainException.class, () -> AccountRefs.fxPosition(null));
        assertThrows(FxDomainException.class, () -> AccountRefs.fxPosition(" "));
    }

    @Test
    void legsForValidatesWalletRefs() {
        assertThrows(FxDomainException.class,
                () -> Conversion.legsFor(null, "wallet:usr_42:KES", SOURCE, TARGET));
        assertThrows(FxDomainException.class,
                () -> Conversion.legsFor(" ", "wallet:usr_42:KES", SOURCE, TARGET));
        assertThrows(FxDomainException.class,
                () -> Conversion.legsFor("wallet:usr_42:USD", null, SOURCE, TARGET));
        assertThrows(FxDomainException.class,
                () -> Conversion.legsFor("wallet:usr_42:USD", "", SOURCE, TARGET));
        assertThrows(NullPointerException.class,
                () -> Conversion.legsFor("wallet:usr_42:USD", "wallet:usr_42:KES", null, TARGET));
        assertThrows(NullPointerException.class,
                () -> Conversion.legsFor("wallet:usr_42:USD", "wallet:usr_42:KES", SOURCE, null));
    }

    @Test
    void rejectsInvalidConstruction() {
        Conversion valid = conversion();
        assertThrows(FxDomainException.class, () -> new Conversion(null, valid.quoteId(),
                valid.sourceWalletRef(), valid.destinationWalletRef(), SOURCE, TARGET, RATE,
                valid.ledgerTxnKey(), valid.ledgerEntryId(), ConversionState.EXECUTED, NOW));
        assertThrows(FxDomainException.class, () -> new Conversion(valid.id(), " ",
                valid.sourceWalletRef(), valid.destinationWalletRef(), SOURCE, TARGET, RATE,
                valid.ledgerTxnKey(), valid.ledgerEntryId(), ConversionState.EXECUTED, NOW));
        assertThrows(FxDomainException.class, () -> new Conversion(valid.id(), valid.quoteId(),
                " ", valid.destinationWalletRef(), SOURCE, TARGET, RATE,
                valid.ledgerTxnKey(), valid.ledgerEntryId(), ConversionState.EXECUTED, NOW));
        assertThrows(FxDomainException.class, () -> new Conversion(valid.id(), valid.quoteId(),
                valid.sourceWalletRef(), null, SOURCE, TARGET, RATE,
                valid.ledgerTxnKey(), valid.ledgerEntryId(), ConversionState.EXECUTED, NOW));
        assertThrows(FxDomainException.class, () -> new Conversion(valid.id(), valid.quoteId(),
                valid.sourceWalletRef(), valid.destinationWalletRef(), SOURCE, TARGET, RATE,
                " ", valid.ledgerEntryId(), ConversionState.EXECUTED, NOW));
        assertThrows(FxDomainException.class, () -> new Conversion(valid.id(), valid.quoteId(),
                valid.sourceWalletRef(), valid.destinationWalletRef(), SOURCE, TARGET, RATE,
                valid.ledgerTxnKey(), " ", ConversionState.EXECUTED, NOW));
        assertThrows(NullPointerException.class, () -> new Conversion(valid.id(), valid.quoteId(),
                valid.sourceWalletRef(), valid.destinationWalletRef(), null, TARGET, RATE,
                valid.ledgerTxnKey(), valid.ledgerEntryId(), ConversionState.EXECUTED, NOW));
        assertThrows(NullPointerException.class, () -> new Conversion(valid.id(), valid.quoteId(),
                valid.sourceWalletRef(), valid.destinationWalletRef(), SOURCE, TARGET, null,
                valid.ledgerTxnKey(), valid.ledgerEntryId(), ConversionState.EXECUTED, NOW));
        assertThrows(NullPointerException.class, () -> new Conversion(valid.id(), valid.quoteId(),
                valid.sourceWalletRef(), valid.destinationWalletRef(), SOURCE, TARGET, RATE,
                valid.ledgerTxnKey(), valid.ledgerEntryId(), null, NOW));
        assertThrows(NullPointerException.class, () -> new Conversion(valid.id(), valid.quoteId(),
                valid.sourceWalletRef(), valid.destinationWalletRef(), SOURCE, TARGET, RATE,
                valid.ledgerTxnKey(), valid.ledgerEntryId(), ConversionState.EXECUTED, null));
        // currencies must match the rate's pair
        assertThrows(com.sharkpay.money.CurrencyMismatchException.class, () -> new Conversion(valid.id(), valid.quoteId(),
                valid.sourceWalletRef(), valid.destinationWalletRef(), SOURCE, Money.of(1270650, "EUR"), RATE,
                valid.ledgerTxnKey(), valid.ledgerEntryId(), ConversionState.EXECUTED, NOW));
    }

    @Test
    void legValidatesItself() {
        assertThrows(FxDomainException.class, () -> new Leg(null, "USD", 10, Direction.DEBIT));
        assertThrows(FxDomainException.class, () -> new Leg("a", "USD", -1, Direction.DEBIT));
        assertThrows(com.sharkpay.money.UnknownCurrencyException.class,
                () -> new Leg("a", "XYZ", 10, Direction.DEBIT));
        assertThrows(NullPointerException.class, () -> new Leg("a", "USD", 10, null));
    }

    private static long sum(List<Leg> legs, String currency, Direction direction) {
        return legs.stream()
                .filter(leg -> leg.currency().equals(currency) && leg.direction() == direction)
                .mapToLong(Leg::amountMinor)
                .sum();
    }
}
