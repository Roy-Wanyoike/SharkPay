package com.sharkpay.reconciliation.domain;

import java.util.Locale;
import java.util.Optional;

/**
 * Provider-status mapping with <b>never-guess</b> semantics, following the
 * providers gateway's mapping table exactly
 * (services/providers/internal/honeycoin/adapter.go):
 *
 * <table border="1">
 *   <caption>Wire status → canonical</caption>
 *   <tr><th>Wire status</th><th>Canonical</th></tr>
 *   <tr><td>PENDING</td><td>PENDING — accepted by rail, awaiting settlement</td></tr>
 *   <tr><td>PROCESSING</td><td>PROCESSING — settlement in flight</td></tr>
 *   <tr><td>CONFIRMED</td><td>CONFIRMED — settled at destination</td></tr>
 *   <tr><td>SUCCEEDED</td><td>CONFIRMED — settled at destination (providers'
 *       internal vocabulary name for the same fact)</td></tr>
 *   <tr><td>FAILED</td><td>FAILED — terminal failure, funds did not move</td></tr>
 *   <tr><td>REVERSED, RETURNED</td><td>RETURNED — funds pulled back /
 *       returned by rail</td></tr>
 *   <tr><td>anything else</td><td><i>empty</i> — ambiguous: the comparison
 *       engine raises a STATUS_MISMATCH break, it never guesses (provider
 *       AMBIGUITY CONTRACT, SECURITY §4)</td></tr>
 * </table>
 *
 * <p>Case and surrounding whitespace are tolerated (the adapter maps with
 * {@code strings.ToUpper(strings.TrimSpace(...))}); everything else is
 * unmappable.</p>
 */
public final class StatusMappings {

    private StatusMappings() {
    }

    /**
     * Maps a raw status string (either side of the comparison) to the
     * canonical vocabulary; empty when the value is blank or unmappable.
     */
    public static Optional<ReconStatus> canonical(String rawStatus) {
        if (rawStatus == null) {
            return Optional.empty();
        }
        String normalized = rawStatus.trim().toUpperCase(Locale.ROOT);
        return switch (normalized) {
            case "PENDING" -> Optional.of(ReconStatus.PENDING);
            case "PROCESSING" -> Optional.of(ReconStatus.PROCESSING);
            case "CONFIRMED", "SUCCEEDED" -> Optional.of(ReconStatus.CONFIRMED);
            case "FAILED" -> Optional.of(ReconStatus.FAILED);
            case "REVERSED", "RETURNED" -> Optional.of(ReconStatus.RETURNED);
            default -> Optional.empty();
        };
    }
}
