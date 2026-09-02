package com.sharkpay.reconciliation.api.dto;

import com.sharkpay.reconciliation.domain.CompensationEntry;

import java.util.List;

/** A list of compensations (per break). */
public record CompensationListJson(List<CompensationJson> compensations, int count) {

    public static CompensationListJson of(List<CompensationEntry> entries) {
        return new CompensationListJson(entries.stream().map(CompensationJson::of).toList(),
                entries.size());
    }
}
