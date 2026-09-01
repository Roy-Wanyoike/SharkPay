package com.sharkpay.wallet.api;

import com.sharkpay.wallet.api.dto.LedgerEventAcceptedJson;
import com.sharkpay.wallet.ledger.LedgerPostingEvent;
import com.sharkpay.wallet.ports.ProjectionStore;
import com.sharkpay.wallet.service.ApplyLedgerEventUseCase;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * Dev/internal ingestion endpoint for the ledger feed: accepts a
 * {@code ledger.posting.committed.v1} CloudEvent envelope (the schema from
 * contracts/events/ledger.posting.v1.json) and feeds the balance
 * projection. In production the NATS/Kafka binding calls the same
 * {@link ApplyLedgerEventUseCase}; this endpoint exists for integration
 * testing and replay tooling. Idempotent: duplicate delivery is a no-op.
 */
@RestController
public final class LedgerEventsController {

    private final ApplyLedgerEventUseCase projector;
    private final ProjectionStore projections;

    public LedgerEventsController(ApplyLedgerEventUseCase projector, ProjectionStore projections) {
        this.projector = projector;
        this.projections = projections;
    }

    @PostMapping("/internal/ledger-events")
    public ResponseEntity<LedgerEventAcceptedJson> ingest(@Valid @RequestBody LedgerPostingEvent event) {
        boolean alreadyApplied = projections.isEventApplied(event.id());
        projector.onLedgerPosting(event);
        int legsApplied = alreadyApplied ? 0 : countAppliedLegs(event);
        return ResponseEntity.accepted().body(new LedgerEventAcceptedJson(event.id(), legsApplied));
    }

    private static int countAppliedLegs(LedgerPostingEvent event) {
        // legs that target a wallet account were newly applied unless the
        // event was a full duplicate; leg-level dedup is the authority, so
        // this count is informational only
        return event.data().postings().size();
    }
}
