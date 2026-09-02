package com.sharkpay.gateway.domain;

import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The HMAC-SHA256 webhook signature: deterministic for the same
 * (secret, timestamp, body), distinct otherwise, header format
 * {@code t=<unix>,v1=<64 hex>} per webhooks.yaml.
 */
class WebhookSignatureTest {

    private static final String SECRET = "whsec_test_signing_secret_0123456789";
    private static final byte[] BODY =
            "{\"id\":\"x\",\"type\":\"payment.succeeded\"}".getBytes(StandardCharsets.UTF_8);

    @Test
    void hmacIsDeterministicForIdenticalInputs() {
        WebhookSignature first = WebhookSignature.sign(SECRET, 1767312000L, BODY);
        WebhookSignature second = WebhookSignature.sign(SECRET, 1767312000L, BODY);
        assertEquals(first.hex(), second.hex());
        assertEquals(first.headerValue(), second.headerValue());
        assertEquals(first.timestamp(), second.timestamp());
    }

    @Test
    void hmacMatchesTheReferenceComputation() throws Exception {
        WebhookSignature signature = WebhookSignature.sign(SECRET, 1767312000L, BODY);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        byte[] message = ("1767312000." + new String(BODY, StandardCharsets.UTF_8))
                .getBytes(StandardCharsets.UTF_8);
        assertEquals(HexFormat.of().formatHex(mac.doFinal(message)), signature.hex());
    }

    @Test
    void hmacCoversTimestampDotBody() throws Exception {
        long timestamp = 1_000_000L;
        WebhookSignature signature = WebhookSignature.sign(SECRET, timestamp, BODY);
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
        // NOT over the body alone...
        assertNotEquals(HexFormat.of().formatHex(mac.doFinal(BODY)), signature.hex());
        // ...but over t + "." + body exactly
        byte[] expected = (timestamp + "." + new String(BodyToString(BODY))).getBytes(StandardCharsets.UTF_8);
        assertEquals(HexFormat.of().formatHex(mac.doFinal(expected)), signature.hex());
    }

    private static String BodyToString(byte[] body) {
        return new String(body, StandardCharsets.UTF_8);
    }

    @Test
    void differentTimestampsDifferentBodiesAndDifferentSecretsAllDiffer() {
        WebhookSignature baseline = WebhookSignature.sign(SECRET, 1767312000L, BODY);
        assertNotEquals(baseline.hex(), WebhookSignature.sign(SECRET, 1767312001L, BODY).hex());
        assertNotEquals(baseline.hex(),
                WebhookSignature.sign(SECRET, 1767312000L, "{\"other\":1}".getBytes(StandardCharsets.UTF_8)).hex());
        assertNotEquals(baseline.hex(), WebhookSignature.sign("whsec_other_secret_0123456789abcdef",
                1767312000L, BODY).hex());
    }

    @Test
    void headerFormatMatchesTheContractPattern() {
        WebhookSignature signature = WebhookSignature.sign(SECRET, 1767312000L, BODY);
        assertEquals("t=1767312000,v1=" + signature.hex(), signature.headerValue());
        assertTrue(signature.headerValue().matches("^t=[0-9]+,v1=[0-9a-f]{64}$"),
                "header must match webhooks.yaml pattern: " + signature.headerValue());
        assertEquals(64, signature.hex().length());
        assertTrue(signature.hex().chars().allMatch(c -> (c >= '0' && c <= '9')
                || (c >= 'a' && c <= 'f')));
    }

    @Test
    void constructorValidatesTheHeaderShape() {
        assertThrows(IllegalArgumentException.class,
                () -> new WebhookSignature(1L, "nothex", "t=1,v1=nothex"));
        assertThrows(IllegalArgumentException.class,
                () -> new WebhookSignature(1L, "a".repeat(64), "t=2,v1=" + "a".repeat(64)));
        assertThrows(IllegalArgumentException.class,
                () -> new WebhookSignature(1L, "A".repeat(64), "t=1,v1=" + "A".repeat(64)));
    }

    @Test
    void emptyBodiesAndZeroTimestampsStillSignDeterministically() {
        byte[] empty = new byte[0];
        WebhookSignature first = WebhookSignature.sign(SECRET, 0L, empty);
        WebhookSignature second = WebhookSignature.sign(SECRET, 0L, empty);
        assertEquals(first.hex(), second.hex());
        assertEquals("t=0,v1=" + first.hex(), first.headerValue());
        assertEquals(64, first.hex().length());
        // the signed message is exactly "0."
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(SECRET.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            assertEquals(HexFormat.of().formatHex(mac.doFinal("0.".getBytes(
                    StandardCharsets.UTF_8))), first.hex());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
