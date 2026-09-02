package com.sharkpay.reconciliation.domain;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Provider-status mapping with never-guess semantics, following the
 * providers gateway's honeycoin mapping table exactly.
 */
class StatusMappingsTest {

    @Test
    void theKnownStatusesMapToTheirCanonicalValues() {
        assertThat(StatusMappings.canonical("PENDING")).contains(ReconStatus.PENDING);
        assertThat(StatusMappings.canonical("PROCESSING")).contains(ReconStatus.PROCESSING);
        assertThat(StatusMappings.canonical("CONFIRMED")).contains(ReconStatus.CONFIRMED);
        assertThat(StatusMappings.canonical("SUCCEEDED")).contains(ReconStatus.CONFIRMED);
        assertThat(StatusMappings.canonical("FAILED")).contains(ReconStatus.FAILED);
        assertThat(StatusMappings.canonical("REVERSED")).contains(ReconStatus.RETURNED);
        assertThat(StatusMappings.canonical("RETURNED")).contains(ReconStatus.RETURNED);
    }

    @Test
    void caseAndSurroundingWhitespaceAreTolerated() {
        assertThat(StatusMappings.canonical(" confirmed ")).contains(ReconStatus.CONFIRMED);
        assertThat(StatusMappings.canonical("Succeeded")).contains(ReconStatus.CONFIRMED);
        assertThat(StatusMappings.canonical("\treturned\n")).contains(ReconStatus.RETURNED);
    }

    @Test
    void unknownOrBlankStatusesAreUnmappableNeverGuessed() {
        assertThat(StatusMappings.canonical("PARTIALLY_SETTLED")).isEmpty();
        assertThat(StatusMappings.canonical("SETTLED")).isEmpty();
        assertThat(StatusMappings.canonical("pending?")).isEmpty();
        assertThat(StatusMappings.canonical("")).isEmpty();
        assertThat(StatusMappings.canonical(null)).isEmpty();
    }

    @Test
    void canonicalStatusWireNamesMatchTheVocabulary() {
        for (ReconStatus status : ReconStatus.values()) {
            assertThat(status.wireName()).isEqualTo(status.name());
        }
        assertThat(ReconStatus.values()).hasSize(5); // no UNKNOWN value —
        // an unmappable status is a STATUS_MISMATCH break, never a value
    }

    @Test
    void theFullOptionalSemanticsRoundTrip() {
        Optional<ReconStatus> mapped = StatusMappings.canonical("REVERSED");
        assertThat(mapped).hasValue(ReconStatus.RETURNED);
        assertThat(mapped.get().wireName()).isEqualTo("RETURNED");
    }
}
