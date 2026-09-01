package com.sharkpay.identity.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Canonical request fingerprints for idempotency: sha-256 over a stable
 * canonical string. Two requests with the same semantic content produce the
 * same fingerprint regardless of JSON formatting.
 */
public final class RequestFingerprint {

    private RequestFingerprint() {
    }

    /**
     * @param command the create-principal command.
     * @return sha-256 hex fingerprint of the canonical form.
     */
    public static String ofCreatePrincipal(CreatePrincipalUseCase.Command command) {
        String owner = command.ownerSharkId() == null ? "" : command.ownerSharkId().value();
        return sha256Hex("create-principal|" + command.type().name() + "|" + owner);
    }

    public static String sha256Hex(String canonical) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(Character.forDigit((b >> 4) & 0xF, 16));
                hex.append(Character.forDigit(b & 0xF, 16));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is not available", e);
        }
    }
}
