package com.sharkpay.risk.domain;

import com.sharkpay.money.Money;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleSetConfigTest {

    private static Map<KycTier, TierLimits> allTiers(TierLimits limits) {
        Map<KycTier, TierLimits> tiers = new EnumMap<>(KycTier.class);
        for (KycTier tier : KycTier.values()) {
            tiers.put(tier, limits);
        }
        return tiers;
    }

    private static RuleSetConfig config(Map<KycTier, TierLimits> tiers) {
        return new RuleSetConfig("rs-1", 1, true, new VelocityPolicy(10, Duration.ofHours(1)),
                tiers, new TierLimits(Money.of(100_00L, "KES"), Money.of(900_00L, "KES"), null),
                Set.of(), Set.of());
    }

    @Test
    void defaultsMatchTheDocumentedPolicy() {
        RuleSetConfig config = RuleSetConfig.defaults();

        assertThat(config.ruleSetId()).isEqualTo("default");
        assertThat(config.version()).isEqualTo(1);
        assertThat(config.active()).isTrue();
        assertThat(config.velocity().maxTransactions()).isEqualTo(10);
        assertThat(config.velocity().window()).isEqualTo(Duration.ofHours(1));
        assertThat(config.geoDenylist()).isEmpty();
        assertThat(config.counterpartyDenylist()).isEmpty();

        TierLimits unverified = config.tierLimits().get(KycTier.UNVERIFIED);
        assertThat(unverified.dailyCap()).isEqualTo(Money.zero("KES"));
        assertThat(unverified.weeklyCap()).isEqualTo(Money.zero("KES"));
        assertThat(unverified.maxSingleLimit()).isEmpty();

        TierLimits limited = config.tierLimits().get(KycTier.LIMITED);
        assertThat(limited.dailyCap()).isEqualTo(Money.of(5_000_00L, "KES"));
        assertThat(limited.weeklyCap()).isEqualTo(Money.of(20_000_00L, "KES"));

        TierLimits full = config.tierLimits().get(KycTier.FULL);
        assertThat(full.dailyCap()).isEqualTo(Money.of(1_000_000_00L, "KES"));
        assertThat(full.weeklyCap()).isEqualTo(Money.of(4_000_000_00L, "KES"));

        TierLimits agent = config.agentLimits();
        assertThat(agent.dailyCap()).isEqualTo(Money.of(500_000_00L, "KES"));
        assertThat(agent.weeklyCap()).isEqualTo(Money.of(2_000_000_00L, "KES"));
        assertThat(agent.maxSingleLimit()).contains(Money.of(150_000_00L, "KES"));
    }

    @Test
    void agentPrincipalsGetTheStricterAgentPolicyRegardlessOfTier() {
        RuleSetConfig config = RuleSetConfig.defaults();

        assertThat(config.limitsFor(PrincipalType.AGENT, KycTier.FULL)).isEqualTo(config.agentLimits());
        assertThat(config.limitsFor(PrincipalType.AGENT, KycTier.UNVERIFIED)).isEqualTo(config.agentLimits());
        assertThat(config.limitsFor(PrincipalType.INDIVIDUAL, KycTier.LIMITED))
                .isEqualTo(config.tierLimits().get(KycTier.LIMITED));
        assertThat(config.limitsFor(PrincipalType.BUSINESS, KycTier.FULL))
                .isEqualTo(config.tierLimits().get(KycTier.FULL));
    }

    @Test
    void validatesRequiredFields() {
        TierLimits limits = new TierLimits(Money.of(1_00L, "KES"), Money.of(9_00L, "KES"), null);

        assertThatThrownBy(() -> new RuleSetConfig(null, 1, true, new VelocityPolicy(1, Duration.ofHours(1)),
                allTiers(limits), limits, Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ruleSetId");
        assertThatThrownBy(() -> new RuleSetConfig(" ", 1, true, new VelocityPolicy(1, Duration.ofHours(1)),
                allTiers(limits), limits, Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ruleSetId");
        assertThatThrownBy(() -> new RuleSetConfig("rs-1", 0, true, new VelocityPolicy(1, Duration.ofHours(1)),
                allTiers(limits), limits, Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("version");
        assertThatThrownBy(() -> new RuleSetConfig("rs-1", 1, true, null,
                allTiers(limits), limits, Set.of(), Set.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("velocity");
        assertThatThrownBy(() -> new RuleSetConfig("rs-1", 1, true, new VelocityPolicy(1, Duration.ofHours(1)),
                null, limits, Set.of(), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tierLimits");
        assertThatThrownBy(() -> new RuleSetConfig("rs-1", 1, true, new VelocityPolicy(1, Duration.ofHours(1)),
                allTiers(limits), null, Set.of(), Set.of()))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("agentLimits");
    }

    @Test
    void requiresLimitsForEveryTier() {
        TierLimits limits = new TierLimits(Money.of(1_00L, "KES"), Money.of(9_00L, "KES"), null);
        Map<KycTier, TierLimits> incomplete = new EnumMap<>(KycTier.class);
        incomplete.put(KycTier.UNVERIFIED, limits);
        incomplete.put(KycTier.LIMITED, limits);

        assertThatThrownBy(() -> config(incomplete))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("missing limits for tier");
    }

    @Test
    void tierLimitsMapIsCopiedAndUnmodifiable() {
        TierLimits limits = new TierLimits(Money.of(1_00L, "KES"), Money.of(9_00L, "KES"), null);
        Map<KycTier, TierLimits> source = allTiers(limits);
        RuleSetConfig config = config(source);

        source.put(KycTier.FULL, new TierLimits(Money.of(999_00L, "KES"), Money.of(999_00L, "KES"), null));
        assertThat(config.tierLimits().get(KycTier.FULL)).isEqualTo(limits);

        assertThatThrownBy(() -> config.tierLimits().put(KycTier.FULL, limits))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void geoDenylistIsNormalizedToUppercaseIsoCodes() {
        TierLimits limits = new TierLimits(Money.of(1_00L, "KES"), Money.of(9_00L, "KES"), null);
        Set<String> raw = new java.util.HashSet<>(java.util.Arrays.asList(" ke ", "", null, "ug"));
        RuleSetConfig config = new RuleSetConfig("rs-1", 1, true, new VelocityPolicy(1, Duration.ofHours(1)),
                allTiers(limits), limits, raw, Set.of());

        assertThat(config.geoDenylist()).containsExactlyInAnyOrder("KE", "UG");

        assertThatThrownBy(() -> new RuleSetConfig("rs-1", 1, true, new VelocityPolicy(1, Duration.ofHours(1)),
                allTiers(limits), limits, Set.of("KEN"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("ISO 3166-1 alpha-2");
        assertThatThrownBy(() -> new RuleSetConfig("rs-1", 1, true, new VelocityPolicy(1, Duration.ofHours(1)),
                allTiers(limits), limits, Set.of("1a"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullDenylistsNormalizeToEmptyAndCounterpartyEntriesAreTrimmed() {
        TierLimits limits = new TierLimits(Money.of(1_00L, "KES"), Money.of(9_00L, "KES"), null);
        RuleSetConfig config = new RuleSetConfig("rs-1", 1, true, new VelocityPolicy(1, Duration.ofHours(1)),
                allTiers(limits), limits, null, null);

        assertThat(config.geoDenylist()).isEmpty();
        assertThat(config.counterpartyDenylist()).isEmpty();

        RuleSetConfig withCounterparties = new RuleSetConfig("rs-1", 1, true,
                new VelocityPolicy(1, Duration.ofHours(1)), allTiers(limits), limits, null,
                new java.util.HashSet<>(java.util.Arrays.asList(" shark_1 ", "  ", null, "shark_2")));
        assertThat(withCounterparties.counterpartyDenylist())
                .containsExactlyInAnyOrder("shark_1", "shark_2");
    }

    @Test
    void equalConfigsAreValueEqual() {
        TierLimits limits = new TierLimits(Money.of(1_00L, "KES"), Money.of(9_00L, "KES"), null);
        RuleSetConfig first = config(allTiers(limits));
        RuleSetConfig second = config(allTiers(limits));

        assertThat(first).isEqualTo(second);
        assertThat(first.hashCode()).isEqualTo(second.hashCode());
        assertThat(first.toString()).contains("rs-1");
    }
}
