package com.sharkpay.identity.domain;

/**
 * Principal type. Agents always reference an owning principal
 * (individual or business) that exists and is ACTIVE.
 */
public enum PrincipalType {
    INDIVIDUAL,
    BUSINESS,
    AGENT
}
