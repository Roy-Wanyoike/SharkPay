package com.sharkpay.risk.storage;

import com.sharkpay.money.Money;
import com.sharkpay.risk.domain.KycTier;
import com.sharkpay.risk.domain.RuleSetConfig;
import com.sharkpay.risk.domain.TierLimits;
import com.sharkpay.risk.domain.VelocityPolicy;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RuleSetMapperTest {

    @Test
    void defaultsRoundTripLosslessly() {
        RuleSetConfig config = RuleSetConfig.defaults();

        String json = RuleSetMapper.toJson(config);
        RuleSetConfig restored = RuleSetMapper.toDomain(json);

        assertThat(restored).isEqualTo(config);
        assertThat(restored.tierLimits()).isEqualTo(config.tierLimits());
        assertThat(restored.agentLimits()).isEqualTo(config.agentLimits());
    }

    @Test
    void jsonDocumentUsesThePersistedWireShape() {
        String json = RuleSetMapper.toJson(RuleSetConfig.defaults());

        assertThat(json)
                .contains("\"rule_set_id\":\"default\"")
                .contains("\"version\":1")
                .contains("\"active\":true")
                .contains("\"velocity\":{\"max_transactions\":10")
                .contains("\"window_seconds\":3600")
                .contains("\"UNVERIFIED\":{\"daily_minor\":0")
                .contains("\"LIMITED\":{\"daily_minor\":500000")
                .contains("\"weekly_minor\":2000000")
                .contains("\"FULL\":{\"daily_minor\":100000000")
                .contains("\"agent_limits\":{\"daily_minor\":50000000")
                .contains("\"max_single_minor\":15000000")
                .contains("\"geo_denylist\":[]")
                .contains("\"counterparty_denylist\":[]");
    }

    @Test
    void denylistsAndMaxSingleRoundTrip() {
        Map<KycTier, TierLimits> tiers = new EnumMap<>(RuleSetConfig.defaults().tierLimits());
        tiers.put(KycTier.LIMITED, new TierLimits(Money.of(1_000_00L, "KES"),
                Money.of(5_000_00L, "KES"), Money.of(500_00L, "KES")));
        TierLimits agent = new TierLimits(Money.of(500_000_00L, "KES"),
                Money.of(2_000_000_00L, "KES"), null);
        RuleSetConfig config = new RuleSetConfig("custom", 7, true,
                new VelocityPolicy(3, Duration.ofMinutes(90)), tiers, agent,
                new HashSet<>(Set.of("KP")), new HashSet<>(Set.of("shark_bad")));

        String json = RuleSetMapper.toJson(config);
        assertThat(json).contains("\"max_single_minor\":50000").contains("\"KP\"").contains("shark_bad");

        RuleSetConfig restored = RuleSetMapper.toDomain(json);
        assertThat(restored).isEqualTo(config);
        assertThat(restored.geoDenylist()).containsExactly("KP");
        assertThat(restored.counterpartyDenylist()).containsExactly("shark_bad");
        assertThat(restored.tierLimits().get(KycTier.LIMITED).maxSingleLimit())
                .contains(Money.of(500_00L, "KES"));
        assertThat(restored.agentLimits().maxSingleLimit()).isEmpty();
    }

    @Test
    void unknownTierKeyFailsParsing() {
        String json = RuleSetMapper.toJson(RuleSetConfig.defaults())
                .replace("\"UNVERIFIED\"", "\"BRONZE\"");

        assertThatThrownBy(() -> RuleSetMapper.toDomain(json))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("tier_limits");
    }

    @Test
    void malformedConfigJsonFailsParsing() {
        String json = RuleSetMapper.toJson(RuleSetConfig.defaults())
                .replace("\"active\":true", "\"active\":maybe");

        assertThatThrownBy(() -> RuleSetMapper.toDomain(json))
                .isInstanceOf(tools.jackson.core.JacksonException.class);
    }
}
