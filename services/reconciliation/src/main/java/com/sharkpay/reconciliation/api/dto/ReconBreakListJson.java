package com.sharkpay.reconciliation.api.dto;

import com.sharkpay.reconciliation.service.BreakView;

import java.util.List;

/** A list of breaks (the recon console view). */
public record ReconBreakListJson(List<ReconBreakJson> breaks, int count) {

    public static ReconBreakListJson of(List<BreakView> views) {
        return new ReconBreakListJson(views.stream().map(ReconBreakJson::of).toList(), views.size());
    }
}
