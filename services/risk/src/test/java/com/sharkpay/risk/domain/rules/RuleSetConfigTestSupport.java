package com.sharkpay.risk.domain.rules;

import com.sharkpay.money.Money;
import com.sharkpay.risk.domain.KycTier;
import com.sharkpay.risk.domain.RuleSetConfig;
import com.sharkpay.risk.domain.TierLimits;
import com.sharkpay.risk.domain.VelocityPolicy;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

/** Builder helpers for rule tests: defaults with one dimension overridden. */
final class RuleSetConfigTestSupport {

    private RuleSetConfigTestSupport() {
    }

    static RuleSetConfig configWithVelocity(int maxTransactions, Duration window) {
        return withVelocity(RuleSetConfig.defaults(), maxTransactions, window);
    }

    static RuleSetConfig withVelocity(RuleSetConfig base, int maxTransactions, Duration window) {
        Map<KycTier, TierLimits> tiers = new EnumMap<>(base.tierLimits());
        return new RuleSetConfig(base.ruleSetId(), base.version(), base.active(),
                new VelocityPolicy(maxTransactions, window), tiers, base.agentLimits(),
                base.geoDenylist(), base.counterpartyDenylist());
    }

    static RuleSetConfig withDenylists(RuleSetConfig base, Set<String> geo, Set<String> counterparties) {
        Map<KycTier, TierLimits> tiers = new EnumMap<>(base.tierLimits());
        return new RuleSetConfig(base.ruleSetId(), base.version(), base.active(), base.velocity(),
                tiers, base.agentLimits(), geo, counterparties);
    }

    static RuleSetConfig zeroAgentSingleCap() {
        RuleSetConfig base = RuleSetConfig.defaults();
        Map<KycTier, TierLimits> tiers = new EnumMap<>(base.tierLimits());
        TierLimits noSingle = new TierLimits(Money.of(500_000_00L, "KES"),
                Money.of(2_000_000_00L, "KES"), null);
        return new RuleSetConfig(base.ruleSetId(), base.version(), base.active(), base.velocity(),
                tiers, noSingle, base.geoDenylist(), base.counterpartyDenylist());
    }
}
