package com.sharkpay.risk.service;

import com.sharkpay.risk.domain.Decision;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AutoCasePolicyTest {

    @Test
    void defaultPolicyOpensOnDenyAndReview() {
        assertThat(AutoCasePolicy.DEFAULT.onDeny()).isTrue();
        assertThat(AutoCasePolicy.DEFAULT.onReview()).isTrue();
    }

    @Test
    void defaultPolicyOpensForEveryNonAllowDecision() {
        AutoCasePolicy policy = AutoCasePolicy.DEFAULT;

        assertThat(policy.opensOn(Decision.DENY)).isTrue();
        assertThat(policy.opensOn(Decision.REVIEW)).isTrue();
        assertThat(policy.opensOn(Decision.ALLOW)).isFalse();
    }

    @Test
    void policiesCanBeTunedPerDecision() {
        AutoCasePolicy denyOnly = new AutoCasePolicy(true, false);
        assertThat(denyOnly.opensOn(Decision.DENY)).isTrue();
        assertThat(denyOnly.opensOn(Decision.REVIEW)).isFalse();
        assertThat(denyOnly.opensOn(Decision.ALLOW)).isFalse();

        AutoCasePolicy reviewOnly = new AutoCasePolicy(false, true);
        assertThat(reviewOnly.opensOn(Decision.DENY)).isFalse();
        assertThat(reviewOnly.opensOn(Decision.REVIEW)).isTrue();
        assertThat(reviewOnly.opensOn(Decision.ALLOW)).isFalse();

        AutoCasePolicy disabled = new AutoCasePolicy(false, false);
        assertThat(disabled.opensOn(Decision.DENY)).isFalse();
        assertThat(disabled.opensOn(Decision.REVIEW)).isFalse();
        assertThat(disabled.opensOn(Decision.ALLOW)).isFalse();
    }
}
