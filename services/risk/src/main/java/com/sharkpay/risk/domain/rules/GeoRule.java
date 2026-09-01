package com.sharkpay.risk.domain.rules;

import com.sharkpay.risk.domain.EvaluationRequest;
import com.sharkpay.risk.domain.Outcome;
import com.sharkpay.risk.domain.Rule;
import com.sharkpay.risk.domain.RuleResult;
import com.sharkpay.risk.domain.RuleSetConfig;
import com.sharkpay.risk.ports.VelocityCounterStore;

/**
 * Geo rule: denies transactions whose {@code geo_country} (ISO 3166-1
 * alpha-2) appears on the configurable deny-list. Default list is empty; a
 * missing country passes (geo signals are optional inputs).
 */
public final class GeoRule implements Rule {

    public static final String ID = "geo_denylist";

    @Override
    public String id() {
        return ID;
    }

    @Override
    public RuleResult evaluate(EvaluationRequest request, RuleSetConfig config, VelocityCounterStore counters) {
        String geo = request.geoCountry();
        if (geo == null) {
            return new RuleResult(ID, Outcome.PASS, "no geo country provided");
        }
        if (config.geoDenylist().contains(geo)) {
            return new RuleResult(ID, Outcome.DENY, "geo country " + geo + " is deny-listed");
        }
        return new RuleResult(ID, Outcome.PASS, "geo country " + geo + " allowed");
    }
}
