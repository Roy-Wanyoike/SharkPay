package com.sharkpay.gateway.domain;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * The webhook delivery signature (webhooks.yaml outbound contract):
 * {@code X-SharkPay-Signature: t=<unix seconds>,v1=<hex hmac-sha256(t + "."
 * + raw body, secret)>} — HMAC-SHA256, deterministic for the same
 * (secret, timestamp, body) triple, 64 lowercase hex chars.
 *
 * <p>Receivers verify {@code v1} over {@code t + "." + raw body} and reject
 * timestamps outside ±5 minutes (docs/BACKEND-DESIGN.md §10). The
 * timestamp is also sent separately as {@code X-SharkPay-Timestamp} and the
 * delivery id as {@code X-SharkPay-Delivery} for receiver-side dedup
 * logging.</p>
 */
public record WebhookSignature(long timestamp, String hex, String headerValue) {

    private static final String HMAC_SHA256 = "HmacSHA256";

    public WebhookSignature {
        if (hex == null || hex.length() != 64
                || !hex.chars().allMatch(c -> (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f'))) {
            throw new IllegalArgumentException("signature must be 64 lowercase hex chars");
        }
        if (headerValue == null || !headerValue.equals("t=" + timestamp + ",v1=" + hex)) {
            throw new IllegalArgumentException("headerValue must be t=<timestamp>,v1=<hex>");
        }
    }

    /**
     * Signs the payload: HMAC-SHA256 over {@code timestamp + "." + body}
     * keyed with the endpoint secret.
     */
    public static WebhookSignature sign(String secret, long timestampEpochSeconds, byte[] body) {
        byte[] key = secret.getBytes(StandardCharsets.UTF_8);
        byte[] message = message(timestampEpochSeconds, body);
        try {
            Mac mac = Mac.getInstance(HMAC_SHA256);
            mac.init(new SecretKeySpec(key, HMAC_SHA256));
            String hex = HexFormat.of().formatHex(mac.doFinal(message));
            return new WebhookSignature(timestampEpochSeconds, hex,
                    "t=" + timestampEpochSeconds + ",v1=" + hex);
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException("HMAC-SHA256 unavailable", e);
        }
    }

    private static byte[] message(long timestamp, byte[] body) {
        String prefix = timestamp + ".";
        byte[] prefixBytes = prefix.getBytes(StandardCharsets.UTF_8);
        byte[] message = new byte[prefixBytes.length + body.length];
        System.arraycopy(prefixBytes, 0, message, 0, prefixBytes.length);
        System.arraycopy(body, 0, message, prefixBytes.length, body.length);
        return message;
    }
}
