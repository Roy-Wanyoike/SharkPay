package com.sharkpay.risk.service;

import com.sharkpay.risk.domain.Decision;

/**
 * Which decisions auto-open a compliance case. Defaults: DENY and REVIEW
 * both open a case (docs/PRD.md D8 "case management for ops review").
 */
public record AutoCasePolicy(boolean onDeny, boolean onReview) {

    public static final AutoCasePolicy DEFAULT = new AutoCasePolicy(true, true);

    public boolean opensOn(Decision decision) {
        return switch (decision) {
            case DENY -> onDeny;
            case REVIEW -> onReview;
            case ALLOW -> false;
        };
    }
}
