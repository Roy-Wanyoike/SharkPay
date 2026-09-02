package com.sharkpay.payouts.domain;

import java.util.Objects;
import java.util.regex.Pattern;

/**
 * External payout destination (contracts/openapi/v1/payouts.yaml
 * PayoutDestination — oneOf discriminated by {@code type}). Construction
 * always validates, so an instance can never hold a malformed msisdn, bank
 * reference or EVM address. Details are fully persisted; events carry only
 * the rail type (redaction per contracts/events/payouts.payout.v1.json).
 */
public record Destination(String type, String msisdn, String bankCode, String accountNumber,
                          String accountName, String country, String network, String address) {

    public static final String TYPE_MPESA = "mpesa";
    public static final String TYPE_BANK = "bank";
    public static final String TYPE_ON_CHAIN = "on_chain";

    /** contracts/openapi/v1/payouts.yaml MpesaDestination.msisdn pattern. */
    public static final Pattern MSISDN_PATTERN = Pattern.compile("^\\+?[1-9][0-9]{6,14}$");
    /** contracts/openapi/v1/payouts.yaml OnChainDestination.address pattern. */
    public static final Pattern EVM_ADDRESS_PATTERN = Pattern.compile("^0x[0-9a-fA-F]{40}$");

    /** Destination type = the wire rail name ("mpesa" | "bank" | "on_chain"). */
    public Destination {
        Objects.requireNonNull(type, "destination type is required");
        String normalizedType = type.trim().toLowerCase().replace('-', '_');
        if (!TYPE_MPESA.equals(normalizedType) && !TYPE_BANK.equals(normalizedType)
                && !TYPE_ON_CHAIN.equals(normalizedType)) {
            throw new UnsupportedDestinationException("unknown destination type: " + type);
        }
        // construction normalizes: the stored type is the canonical wire name
        // and detail fields are trimmed, so rail()/describe()/persistence can
        // never see a variant spelling ("MPESA", "on-chain", padded text)
        type = normalizedType;
        msisdn = trimToNull(msisdn);
        bankCode = trimToNull(bankCode);
        accountNumber = trimToNull(accountNumber);
        accountName = trimToNull(accountName);
        country = trimToNull(country);
        network = trimToNull(network);
        address = trimToNull(address);
        switch (normalizedType) {
            case TYPE_MPESA -> {
                requireText(msisdn, "msisdn");
                if (!MSISDN_PATTERN.matcher(msisdn).matches()) {
                    throw new UnsupportedDestinationException("msisdn must match E.164 pattern: "
                            + msisdn);
                }
            }
            case TYPE_BANK -> {
                requireText(bankCode, "bank_code");
                requireText(accountNumber, "account_number");
                if (bankCode.length() > 64) {
                    throw new UnsupportedDestinationException("bank_code too long");
                }
                if (accountNumber.length() > 64) {
                    throw new UnsupportedDestinationException("account_number too long");
                }
            }
            case TYPE_ON_CHAIN -> {
                requireText(network, "network");
                requireText(address, "address");
                if (!network.equals("base") && !network.equals("ethereum")
                        && !network.equals("polygon")) {
                    throw new UnsupportedDestinationException("unknown on-chain network: " + network);
                }
                if (!EVM_ADDRESS_PATTERN.matcher(address).matches()) {
                    throw new UnsupportedDestinationException(
                            "address must be a 20-byte hex EVM address: " + address);
                }
            }
            default -> throw new UnsupportedDestinationException("unknown destination type: " + type);
        }
    }

    /** The rail this destination routes onto (1:1 with the contract). */
    public Rail rail() {
        return switch (type) {
            case TYPE_MPESA -> Rail.MPESA;
            case TYPE_BANK -> Rail.BANK;
            case TYPE_ON_CHAIN -> Rail.ON_CHAIN;
            default -> throw new IllegalStateException("unreachable destination type: " + type);
        };
    }

    /** Normalized single-line rendering for audit/reason strings. */
    public String describe() {
        return switch (type) {
            case TYPE_MPESA -> "mpesa:" + msisdn;
            case TYPE_BANK -> "bank:" + bankCode + ":" + accountNumber;
            case TYPE_ON_CHAIN -> "on_chain:" + network + ":" + address;
            default -> type;
        };
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new UnsupportedDestinationException(field + " is required for a "
                    + "destination of this type");
        }
    }

    private static String trimToNull(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
