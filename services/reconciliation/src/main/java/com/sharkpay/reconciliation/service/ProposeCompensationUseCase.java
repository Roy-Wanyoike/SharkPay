package com.sharkpay.reconciliation.service;

import com.sharkpay.reconciliation.domain.CompensationEntry;
import com.sharkpay.reconciliation.domain.CompensationLeg;
import com.sharkpay.reconciliation.domain.IdempotencyConflictException;
import com.sharkpay.reconciliation.domain.ReconBreak;
import com.sharkpay.reconciliation.domain.ReconciliationStateException;
import com.sharkpay.reconciliation.ports.CompensationEntryRepository;
import com.sharkpay.reconciliation.ports.IdempotencyStore;
import com.sharkpay.reconciliation.ports.Randomness;
import com.sharkpay.reconciliation.ports.ReconBreakRepository;

import java.time.Clock;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.StringJoiner;
import java.util.UUID;

/**
 * Operator A drafts the compensation for a break (RB-7 step 2): the legs
 * that make both sides agree — typically involving
 * {@code suspense:recon:KES} or {@code honeycoin:settlement:KES} — with a
 * reason and an optional prior entry to compensate (a REVERSAL). The entry
 * is created PROPOSED; nothing posts to the ledger until a distinct
 * approver executes it.
 *
 * <p>Idempotent on the Idempotency-Key: a replay with the same payload
 * returns the original proposal; a different payload is a 409 conflict.
 * The ledger-side idempotency key (RB-7 step 4) is
 * {@code ops:adj:<breakId>} for the first compensation and
 * {@code ops:adj:<breakId>#<n>} for later ones (a wrong compensation is
 * corrected by another compensation, RB-7 rollback).</p>
 */
public final class ProposeCompensationUseCase {

    private final CompensationEntryRepository compensations;
    private final ReconBreakRepository breaks;
    private final IdempotencyStore idempotency;
    private final Randomness randomness;
    private final Clock clock;

    public ProposeCompensationUseCase(CompensationEntryRepository compensations,
                                      ReconBreakRepository breaks, IdempotencyStore idempotency,
                                      Randomness randomness, Clock clock) {
        this.compensations = Objects.requireNonNull(compensations,
                "compensationEntryRepository is required");
        this.breaks = Objects.requireNonNull(breaks, "reconBreakRepository is required");
        this.idempotency = Objects.requireNonNull(idempotency, "idempotencyStore is required");
        this.randomness = Objects.requireNonNull(randomness, "randomness is required");
        this.clock = Objects.requireNonNull(clock, "clock is required");
    }

    /**
     * @param idempotencyKey client Idempotency-Key (required, non-blank)
     * @param breakId        the break to compensate (must be active)
     * @param requester      operator A (recorded before execution)
     * @param reason         why this entry makes both sides agree
     * @param legs           the drafted legs (≥ 2, balanced per currency)
     * @param reversesEntryId optional prior entry a REVERSAL compensates
     */
    public Result propose(String idempotencyKey, String breakId, String requester, String reason,
                          List<CompensationLeg> legs, UUID reversesEntryId) {
        requireKey(idempotencyKey);
        ReconBreak break_ = breaks.findById(breakId)
                .orElseThrow(() -> new NoSuchElementException("recon break " + breakId + " not found"));
        if (!break_.state().isActive()) {
            throw new ReconciliationStateException(
                    "break " + breakId + " is " + break_.state().wireName()
                            + "; a terminal break is never compensated (correct with a new "
                            + "compensation entry on an active break instead)");
        }
        Objects.requireNonNull(legs, "legs is required");
        // canonical principal: the 4-eyes comparison in
        // ApproveAndExecuteCompensationUseCase trims the approver, so the
        // requester must be stored trimmed too — "ops.alice" and
        // " ops.alice " are the same person and must never be two
        String canonicalRequester = requester == null ? null : requester.trim();
        String fingerprint = fingerprint(breakId, canonicalRequester, reason, legs,
                reversesEntryId);

        java.util.Optional<IdempotencyStore.StoredRequest> stored =
                idempotency.find(IdempotencyStore.Scope.PROPOSE_COMPENSATION, idempotencyKey.trim());
        if (stored.isPresent()) {
            if (!stored.get().requestFingerprint().equals(fingerprint)) {
                throw new IdempotencyConflictException(idempotencyKey);
            }
            CompensationEntry original = compensations.findById(stored.get().entityId())
                    .orElseThrow(() -> new NoSuchElementException(
                            "compensation " + stored.get().entityId() + " referenced by idempotency "
                                    + "key " + idempotencyKey + " is missing"));
            return new Result(original, true);
        }

        long prior = compensations.countByBreak(breakId);
        String compensationKey = prior == 0
                ? CompensationEntry.KEY_PREFIX + breakId
                : CompensationEntry.KEY_PREFIX + breakId + "#" + (prior + 1);

        CompensationEntry entry = CompensationEntry.propose(randomness.compensationId(), breakId,
                break_.provider(), compensationKey, canonicalRequester, reason, legs,
                reversesEntryId);
        compensations.save(entry);
        idempotency.put(IdempotencyStore.Scope.PROPOSE_COMPENSATION, idempotencyKey.trim(),
                new IdempotencyStore.StoredRequest(fingerprint, entry.id()));
        return new Result(entry, false);
    }

    /** Canonical request fingerprint (leg order included). */
    static String fingerprint(String breakId, String requester, String reason,
                              List<CompensationLeg> legs, UUID reversesEntryId) {
        StringJoiner joiner = new StringJoiner(";");
        for (CompensationLeg leg : legs) {
            joiner.add(leg.accountRef() + ":" + leg.direction().wireName() + ":"
                    + leg.amount().amountMinor() + ":" + leg.amount().currency());
        }
        return "PROPOSE_COMPENSATION|" + breakId + "|" + requester + "|" + reason + "|"
                + joiner + "|" + (reversesEntryId == null ? "-" : reversesEntryId);
    }

    private static void requireKey(String key) {
        if (key == null || key.isBlank()) {
            throw new IllegalArgumentException("Idempotency-Key header must not be blank");
        }
        if (key.length() > 128) {
            throw new IllegalArgumentException("Idempotency-Key header must be at most 128 characters");
        }
    }

    /** @param replay true when served from the idempotency store */
    public record Result(CompensationEntry entry, boolean replay) {
    }
}
