package com.sharkpay.wallet.ledger;

import com.sharkpay.wallet.domain.Direction;
import com.sharkpay.wallet.domain.Source;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class LedgerPostingEventTest {

    private static final Instant T0 = Instant.parse("2026-09-01T10:00:02Z");
    private static final UUID ENTRY = UUID.randomUUID();
    private static final UUID SOURCE_REF = UUID.randomUUID();

    @Test
    void wellFormedEventValidatesAndExposesLegSemantics() {
        LedgerPostingEvent event = validEvent();

        event.validate();

        assertThat(event.id()).isNotBlank();
        assertThat(event.type()).isEqualTo("ledger.posting.committed.v1");
        assertThat(event.specversion()).isEqualTo("1.0");
        assertThat(event.source()).isEqualTo("sharkpay/ledger");
        assertThat(event.subject()).isEqualTo(ENTRY.toString());
        assertThat(event.occurred_at()).isEqualTo(T0);
        assertThat(event.data().entry_id()).isEqualTo(ENTRY);
        assertThat(event.data().transaction_key()).isEqualTo("payments:xyz:capture");
        assertThat(event.data().source()).isEqualTo(Source.PAYMENTS);
        assertThat(event.data().source_ref()).isEqualTo(SOURCE_REF);
        assertThat(event.data().entry_type()).isEqualTo("capture");

        LedgerPostingEvent.Posting walletLeg = event.data().postings().get(0);
        assertThat(walletLeg.direction()).isEqualTo(Direction.CREDIT);
        assertThat(walletLeg.amountMinor()).isEqualTo(150000L);
    }

    @Test
    void envelopeConstantsAreEnforced() {
        assertThatThrownBy(() -> withType("wallet.balance.changed.v1").validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("event type");
        assertThatThrownBy(() -> withSpecversion("0.3").validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("specversion");
        assertThatThrownBy(() -> withSource("sharkpay/wallet").validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("source");
    }

    @Test
    void entriesNeedAtLeastTwoLegs() {
        assertThatThrownBy(() -> event(List.of(leg(101, 100, 0))).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("at least 2 legs");
    }

    @Test
    void unknownEntryTypesAreRejected() {
        assertThatThrownBy(() -> withEntryType("guess").validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("entry_type");
    }

    @Test
    void postingIdsMustBeStrictlyIncreasingWithinAnEntry() {
        assertThatThrownBy(() -> event(List.of(leg(102, 0, 100), leg(102, 100, 0))).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly increasing");
        assertThatThrownBy(() -> event(List.of(leg(103, 0, 100), leg(102, 100, 0))).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("strictly increasing");
    }

    @Test
    void legsAreOneSidedAndNonNegative() {
        assertThatThrownBy(() -> event(List.of(leg(101, 100, 100), leg(102, 0, 100))).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one-sided");
        assertThatThrownBy(() -> event(List.of(leg(101, 0, 0), leg(102, 0, 100))).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("one-sided");
        assertThatThrownBy(() -> event(List.of(leg(101, -1, 0), leg(102, 0, 100))).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("non-negative");
        assertThatThrownBy(() -> event(List.of(leg(0, 100, 0), leg(1, 0, 100))).validate())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("posting_id");
    }

    @Test
    void nullMandatoryFieldsAreRejected() {
        assertThatThrownBy(() -> new LedgerPostingEvent(null, LedgerPostingEvent.TYPE,
                "1.0", LedgerPostingEvent.SOURCE, ENTRY.toString(), T0, data("capture", List.of())))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LedgerPostingEvent("id", LedgerPostingEvent.TYPE,
                "1.0", LedgerPostingEvent.SOURCE, ENTRY.toString(), null, data("capture",
                List.of(leg(101, 0, 100), leg(102, 100, 0)))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("occurred_at");
        assertThatThrownBy(() -> new LedgerPostingEvent("id", LedgerPostingEvent.TYPE,
                "1.0", LedgerPostingEvent.SOURCE, ENTRY.toString(), T0, null))
                .isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> new LedgerPostingEvent.LedgerData(null, "key", Source.OPS,
                SOURCE_REF, "capture", null, null, null, List.of()))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void postingsCopyIsDefensive() {
        List<LedgerPostingEvent.Posting> legs = new java.util.ArrayList<>();
        legs.add(leg(101, 0, 100));
        LedgerPostingEvent.LedgerData data = data("capture", legs);
        legs.add(leg(102, 100, 0));
        assertThat(data.postings()).hasSize(1);
    }

    // ------------------------------------------------------------------
    // builders
    // ------------------------------------------------------------------

    private static LedgerPostingEvent validEvent() {
        return event(List.of(leg(101, 0, 150000), leg(102, 150000, 0)));
    }

    private static LedgerPostingEvent event(List<LedgerPostingEvent.Posting> postings) {
        return new LedgerPostingEvent(UUID.randomUUID().toString(), LedgerPostingEvent.TYPE,
                LedgerPostingEvent.SPECVERSION, LedgerPostingEvent.SOURCE, ENTRY.toString(), T0,
                data("capture", postings));
    }

    private static LedgerPostingEvent withType(String type) {
        return new LedgerPostingEvent(UUID.randomUUID().toString(), type,
                LedgerPostingEvent.SPECVERSION, LedgerPostingEvent.SOURCE, ENTRY.toString(), T0,
                data("capture", List.of(leg(101, 0, 100), leg(102, 100, 0))));
    }

    private static LedgerPostingEvent withSpecversion(String specversion) {
        return new LedgerPostingEvent(UUID.randomUUID().toString(), LedgerPostingEvent.TYPE,
                specversion, LedgerPostingEvent.SOURCE, ENTRY.toString(), T0,
                data("capture", List.of(leg(101, 0, 100), leg(102, 100, 0))));
    }

    private static LedgerPostingEvent withSource(String source) {
        return new LedgerPostingEvent(UUID.randomUUID().toString(), LedgerPostingEvent.TYPE,
                LedgerPostingEvent.SPECVERSION, source, ENTRY.toString(), T0,
                data("capture", List.of(leg(101, 0, 100), leg(102, 100, 0))));
    }

    private static LedgerPostingEvent withEntryType(String entryType) {
        return new LedgerPostingEvent(UUID.randomUUID().toString(), LedgerPostingEvent.TYPE,
                LedgerPostingEvent.SPECVERSION, LedgerPostingEvent.SOURCE, ENTRY.toString(), T0,
                data(entryType, List.of(leg(101, 0, 100), leg(102, 100, 0))));
    }

    private static LedgerPostingEvent.LedgerData data(String entryType,
                                                      List<LedgerPostingEvent.Posting> postings) {
        return new LedgerPostingEvent.LedgerData(ENTRY, "payments:xyz:capture", Source.PAYMENTS,
                SOURCE_REF, entryType, null, null, null, postings);
    }

    private static LedgerPostingEvent.Posting leg(long postingId, long debit, long credit) {
        return new LedgerPostingEvent.Posting(postingId, UUID.randomUUID(),
                "wallet:usr_1:KES", "KES", debit, credit);
    }
}
