package com.sharkpay.risk.domain.rules;

import com.sharkpay.money.Money;
import com.sharkpay.risk.domain.EvaluationRequest;
import com.sharkpay.risk.domain.KycTier;
import com.sharkpay.risk.domain.Outcome;
import com.sharkpay.risk.domain.PrincipalType;
import com.sharkpay.risk.domain.Rule;
import com.sharkpay.risk.domain.RuleResult;
import com.sharkpay.risk.domain.RuleSetConfig;
import com.sharkpay.risk.domain.TierLimits;
import com.sharkpay.risk.ports.VelocityCounterStore;

import java.time.Duration;
import java.util.Optional;

/**
 * Per-tier daily/weekly amount caps plus the stricter agent policy (single
 * transaction cap).
 *
 * Same-currency comparison only: when the transaction currency differs from
 * the policy currency the rule PASSES and reports that cross-currency limit
 * checking is deferred to the integration phase (FX-service rate feed).
 * Cumulative sums come from the windowed counter store (rolling 24h / 7d
 * buckets keyed on the subject principal) and only contain ALLOWED
 * transactions — same ordering contract as the velocity rule.
 */
public final class LimitRule implements Rule {

    public static final String ID = "tier_limit";

    private static final Duration ONE_DAY = Duration.ofHours(24);
    private static final Duration ONE_WEEK = Duration.ofDays(7);

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RuleResult evaluate(EvaluationRequest request, RuleSetConfig config, VelocityCounterStore counters) {
        PrincipalType type = request.principalType();
        KycTier tier = request.kycTier();
        TierLimits limits = config.limitsFor(type, tier);
        Money amount = request.amount();

        Optional<Money> single = limits.maxSingleLimit();
        if (single.isPresent() && single.get().currency().equals(amount.currency())
                && amount.compareTo(single.get()) > 0) {
            return new RuleResult(ID, Outcome.DENY,
                    "single-transaction cap exceeded for " + type.wire() + ": " + minor(amount)
                            + " > " + minor(single.get()));
        }

        String currency = limits.dailyCap().currency();
        if (!currency.equals(amount.currency())) {
            return new RuleResult(ID, Outcome.PASS,
                    "cross-currency limit check deferred (caps in " + currency + ", transaction in "
                            + amount.currency() + ")");
        }

        Money dailySoFar = counters.amountInWindow(request.subjectPrincipalId(), currency, ONE_DAY);
        if (dailySoFar.add(amount).compareTo(limits.dailyCap()) > 0) {
            return new RuleResult(ID, Outcome.DENY, dailyDenyReason(type, tier, limits.dailyCap(), dailySoFar, amount));
        }

        Money weeklySoFar = counters.amountInWindow(request.subjectPrincipalId(), currency, ONE_WEEK);
        if (weeklySoFar.add(amount).compareTo(limits.weeklyCap()) > 0) {
            return new RuleResult(ID, Outcome.DENY,
                    "weekly cap exceeded for " + type.wire() + "/" + tier.wire() + ": " + minor(weeklySoFar)
                            + " + " + minor(amount) + " > " + minor(limits.weeklyCap()));
        }

        return new RuleResult(ID, Outcome.PASS,
                "within limits (" + type.wire() + "/" + tier.wire() + "): daily " + minor(dailySoFar) + " + "
                        + minor(amount) + " <= " + minor(limits.dailyCap()));
    }

    private static String dailyDenyReason(PrincipalType type, KycTier tier, Money cap, Money soFar, Money amount) {
        if (cap.isZero()) {
            return "kyc tier " + tier.wire() + " has a zero " + type.wire()
                    + " daily cap: money movement requires KYC";
        }
        return "daily cap exceeded for " + type.wire() + "/" + tier.wire() + ": " + minor(soFar) + " + "
                + minor(amount) + " > " + minor(cap);
    }

    private static String minor(Money money) {
        return money.amountMinor() + " " + money.currency() + "-minor";
    }
}
