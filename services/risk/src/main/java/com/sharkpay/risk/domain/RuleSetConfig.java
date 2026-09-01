package com.sharkpay.risk.domain;

import java.util.Collections;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Configuration snapshot of the active rule set (persisted as the
 * {@code rule_sets.config} jsonb document). The in-memory
 * {@link #defaults()} factory is authoritative until rule-set administration
 * lands (WP-10 ops console); the repository port exists so the storage
 * adapter can override it.
 */
public record RuleSetConfig(
        String ruleSetId,
        long version,
        boolean active,
        VelocityPolicy velocity,
        Map<KycTier, TierLimits> tierLimits,
        TierLimits agentLimits,
        Set<String> geoDenylist,
        Set<String> counterpartyDenylist) {

    public RuleSetConfig {
        if (ruleSetId == null || ruleSetId.isBlank()) {
            throw new IllegalArgumentException("ruleSetId must not be blank");
        }
        if (version < 1) {
            throw new IllegalArgumentException("version must be >= 1, got " + version);
        }
        Objects.requireNonNull(velocity, "velocity policy must not be null");
        if (tierLimits == null) {
            throw new IllegalArgumentException("tierLimits must not be null");
        }
        Map<KycTier, TierLimits> copy = new EnumMap<>(KycTier.class);
        copy.putAll(tierLimits);
        for (KycTier tier : KycTier.values()) {
            if (!copy.containsKey(tier)) {
                throw new IllegalArgumentException("tierLimits is missing limits for tier " + tier);
            }
        }
        tierLimits = Collections.unmodifiableMap(copy);
        Objects.requireNonNull(agentLimits, "agentLimits must not be null");
        geoDenylist = normalizeGeoDenylist(geoDenylist);
        counterpartyDenylist = normalizeCounterpartyDenylist(counterpartyDenylist);
    }

    /**
     * Default policy (documented defaults):
     * <ul>
     *   <li>velocity: 10 transactions / hour</li>
     *   <li>UNVERIFIED: zero caps (money movement requires KYC)</li>
     *   <li>LIMITED: daily 5000.00 KES, weekly 20000.00 KES</li>
     *   <li>FULL: daily 1,000,000.00 KES, weekly 4,000,000.00 KES</li>
     *   <li>AGENT (stricter, regardless of tier): daily 500,000.00 KES,
     *       weekly 2,000,000.00 KES, single-transaction cap 150,000.00 KES</li>
     *   <li>geo and counterparty deny-lists empty</li>
     * </ul>
     */
    public static RuleSetConfig defaults() {
        Map<KycTier, TierLimits> tiers = new EnumMap<>(KycTier.class);
        tiers.put(KycTier.UNVERIFIED, new TierLimits(
                com.sharkpay.money.Money.zero("KES"), com.sharkpay.money.Money.zero("KES"), null));
        tiers.put(KycTier.LIMITED, new TierLimits(
                com.sharkpay.money.Money.of(5_000_00L, "KES"), com.sharkpay.money.Money.of(20_000_00L, "KES"), null));
        tiers.put(KycTier.FULL, new TierLimits(
                com.sharkpay.money.Money.of(1_000_000_00L, "KES"),
                com.sharkpay.money.Money.of(4_000_000_00L, "KES"), null));
        TierLimits agent = new TierLimits(
                com.sharkpay.money.Money.of(500_000_00L, "KES"),
                com.sharkpay.money.Money.of(2_000_000_00L, "KES"),
                com.sharkpay.money.Money.of(150_000_00L, "KES"));
        return new RuleSetConfig("default", 1, true,
                new VelocityPolicy(10, java.time.Duration.ofHours(1)), tiers, agent, Set.of(), Set.of());
    }

    /**
     * Limits applying to a request: agent principals always get the stricter
     * agent policy; everyone else is capped by KYC tier.
     */
    public TierLimits limitsFor(PrincipalType principalType, KycTier tier) {
        return principalType == PrincipalType.AGENT ? agentLimits : tierLimits.get(tier);
    }

    private static Set<String> normalizeGeoDenylist(Set<String> raw) {
        if (raw == null) {
            return Set.of();
        }
        Set<String> normalized = new HashSet<>();
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            String upper = entry.trim().toUpperCase(Locale.ROOT);
            if (!upper.matches("[A-Z]{2}")) {
                throw new IllegalArgumentException("geo deny-list entries must be ISO 3166-1 alpha-2 codes: '" + entry + "'");
            }
            normalized.add(upper);
        }
        return Set.copyOf(normalized);
    }

    private static Set<String> normalizeCounterpartyDenylist(Set<String> raw) {
        if (raw == null) {
            return Set.of();
        }
        Set<String> normalized = new HashSet<>();
        for (String entry : raw) {
            if (entry == null || entry.isBlank()) {
                continue;
            }
            normalized.add(entry.trim());
        }
        return Set.copyOf(normalized);
    }
}
