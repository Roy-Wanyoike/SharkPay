package com.sharkpay.risk.storage;

import com.sharkpay.money.Money;
import com.sharkpay.risk.domain.KycTier;
import com.sharkpay.risk.domain.RuleSetConfig;
import com.sharkpay.risk.domain.TierLimits;
import com.sharkpay.risk.domain.VelocityPolicy;
import com.sharkpay.risk.domain.WireValue;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * rule_sets.config (jsonb) &lt;-&gt; {@link RuleSetConfig}. The JSON document
 * is the integration surface for future rule-set administration (WP-10 ops
 * console writes this shape via the API/CLI).
 */
public final class RuleSetMapper {

    private static final tools.jackson.databind.ObjectMapper JSON = new tools.jackson.databind.ObjectMapper();

    private RuleSetMapper() {
    }

    public static String toJson(RuleSetConfig config) {
        Map<String, PersistedShapes.TierLimits> tiers = new HashMap<>();
        config.tierLimits().forEach((tier, limits) -> tiers.put(tier.name(), toPersisted(limits)));
        PersistedShapes.RuleSet persisted = new PersistedShapes.RuleSet(
                config.ruleSetId(),
                config.version(),
                config.active(),
                new PersistedShapes.Velocity(
                        config.velocity().maxTransactions(),
                        config.velocity().window().toSeconds()),
                tiers,
                toPersisted(config.agentLimits()),
                List.copyOf(config.geoDenylist()),
                List.copyOf(config.counterpartyDenylist()));
        return JSON.writeValueAsString(persisted);
    }

    public static RuleSetConfig toDomain(String json) {
        PersistedShapes.RuleSet persisted = JSON.readValue(json, PersistedShapes.RuleSet.class);
        Map<KycTier, TierLimits> tiers = new EnumMap<>(KycTier.class);
        persisted.tierLimits().forEach((key, limits) ->
                tiers.put(WireValue.parse(KycTier.class, key, "tier_limits key"), toDomain(limits)));
        return new RuleSetConfig(
                persisted.ruleSetId(),
                persisted.version(),
                persisted.active(),
                new VelocityPolicy(
                        persisted.velocity().maxTransactions(),
                        Duration.ofSeconds(persisted.velocity().windowSeconds())),
                tiers,
                toDomain(persisted.agentLimits()),
                new HashSet<>(persisted.geoDenylist()),
                new HashSet<>(persisted.counterpartyDenylist()));
    }

    private static PersistedShapes.TierLimits toPersisted(TierLimits limits) {
        return new PersistedShapes.TierLimits(
                limits.dailyCap().amountMinor(),
                limits.weeklyCap().amountMinor(),
                limits.maxSingle() == null ? null : limits.maxSingle().amountMinor(),
                limits.dailyCap().currency());
    }

    private static TierLimits toDomain(PersistedShapes.TierLimits persisted) {
        Money daily = Money.of(persisted.dailyMinor(), persisted.currency());
        Money weekly = Money.of(persisted.weeklyMinor(), persisted.currency());
        Money single = persisted.maxSingleMinor() == null ? null : Money.of(persisted.maxSingleMinor(), persisted.currency());
        return new TierLimits(daily, weekly, single);
    }
}
