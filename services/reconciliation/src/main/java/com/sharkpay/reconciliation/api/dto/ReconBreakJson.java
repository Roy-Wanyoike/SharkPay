package com.sharkpay.reconciliation.api.dto;

import com.sharkpay.reconciliation.service.BreakView;

import java.time.Instant;

/**
 * A break on the wire: the full both-side facts, the lifecycle, the live
 * aging bucket + age, and the RB-7 audit fields (note, actor, compensation
 * link, escalation timestamp). Absent-side fields are omitted (NON_NULL).
 */
public record ReconBreakJson(String id, String run_id, String provider, String break_type,
                             String provider_ref, String internal_ref, MoneyJson provider_amount,
                             MoneyJson internal_amount, MoneyJson provider_fee,
                             MoneyJson internal_fee, String provider_status,
                             String internal_status, String state, String bucket, long age_hours,
                             Instant detected_at, String note, String last_actor,
                             Instant last_transition_at, String compensation_id,
                             Instant resolved_at, Instant escalated_at) {

    public static ReconBreakJson of(BreakView view) {
        var break_ = view.break_();
        return new ReconBreakJson(break_.id(), break_.runId(), break_.provider(),
                break_.breakType().wireName(), break_.providerRef(), break_.internalRef(),
                MoneyJson.of(break_.providerAmount()), MoneyJson.of(break_.internalAmount()),
                MoneyJson.of(break_.providerFee()), MoneyJson.of(break_.internalFee()),
                break_.providerStatus(), break_.internalStatus(), break_.state().wireName(),
                view.bucket().wireName(), view.ageHours(), break_.detectedAt(), break_.note(),
                break_.lastActor(), break_.lastTransitionAt(), break_.compensationId(),
                break_.resolvedAt(), break_.escalatedAt());
    }
}
