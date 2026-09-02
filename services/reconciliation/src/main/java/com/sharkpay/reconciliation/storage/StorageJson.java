package com.sharkpay.reconciliation.storage;

import com.sharkpay.reconciliation.domain.CompensationLeg;
import com.sharkpay.reconciliation.domain.PostingDirection;
import com.sharkpay.reconciliation.domain.SettlementReport;
import com.sharkpay.money.Money;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * JSON codec for the two JSON-typed columns (legs on compensation entries,
 * currency lines on settlement reports) — storage-internal format, exact
 * round-trip, integer minor units only. Uses the same tools.jackson mapper
 * family as the rest of the service (Jackson 3, no com.fasterxml databind).
 */
final class StorageJson {

    private static final JsonMapper MAPPER = JsonMapper.builder().build();

    private static final TypeReference<List<LegJson>> LEGS_TYPE = new TypeReference<>() {
    };

    private static final TypeReference<List<CurrencyLineJson>> CURRENCY_LINES_TYPE =
            new TypeReference<>() {
            };

    private StorageJson() {
    }

    /** One compensation leg (storage shape). */
    record LegJson(String account_ref, String direction, long amount_minor, String currency) {

        CompensationLeg toDomain() {
            return new CompensationLeg(account_ref, PostingDirection.fromWireName(direction),
                    Money.of(amount_minor, currency));
        }

        static LegJson from(CompensationLeg leg) {
            return new LegJson(leg.accountRef(), leg.direction().wireName(),
                    leg.amount().amountMinor(), leg.amount().currency());
        }
    }

    /** One settlement currency line (storage shape). */
    record CurrencyLineJson(String currency, int provider_lines, long provider_volume_minor,
                            long provider_fees_minor, int internal_lines, long internal_volume_minor,
                            long internal_fees_minor, int matched_lines) {

        SettlementReport.CurrencyLine toDomain() {
            return new SettlementReport.CurrencyLine(currency, provider_lines,
                    provider_volume_minor, provider_fees_minor, internal_lines,
                    internal_volume_minor, internal_fees_minor, matched_lines);
        }

        static CurrencyLineJson from(SettlementReport.CurrencyLine line) {
            return new CurrencyLineJson(line.currency(), line.providerLines(), line.providerVolume(),
                    line.providerFees(), line.internalLines(), line.internalVolume(),
                    line.internalFees(), line.matchedPairs());
        }
    }

    static String writeLegs(List<CompensationLeg> legs) {
        return MAPPER.writeValueAsString(legs.stream().map(LegJson::from).toList());
    }

    static List<CompensationLeg> readLegs(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return MAPPER.readValue(json, LEGS_TYPE).stream()
                .map(LegJson::toDomain)
                .toList();
    }

    static String writeCurrencyLines(List<SettlementReport.CurrencyLine> lines) {
        return MAPPER.writeValueAsString(lines.stream().map(CurrencyLineJson::from).toList());
    }

    static List<SettlementReport.CurrencyLine> readCurrencyLines(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        return MAPPER.readValue(json, CURRENCY_LINES_TYPE).stream()
                .map(CurrencyLineJson::toDomain)
                .toList();
    }
}
