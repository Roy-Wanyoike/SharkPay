package com.sharkpay.gateway.webhook;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.sharkpay.gateway.ports.WebhookSender;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The one real wire adapter, exercised against a loopback
 * {@code com.sun.net.httpserver} server (never an external host, ADR 003):
 * any 2xx counts as delivered (body ignored), non-2xx is a rejected
 * attempt, transport failures (connect refused, bad URL) are failed
 * attempts with no status, and the signature headers travel the wire.
 */
class JdkHttpClientWebhookSenderTest {

    private HttpServer server;

    @AfterEach
    void stopServer() {
        if (server != null) {
            server.stop(0);
        }
    }

    /** Starts a loopback server answering every POST with the given status. */
    private int startServer(int status) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        AtomicInteger requests = new AtomicInteger();
        AtomicReference<Map<String, List<String>>> capturedHeaders = new AtomicReference<>();
        AtomicReference<byte[]> capturedBody = new AtomicReference<>();
        server.createContext("/hooks", (HttpExchange exchange) -> {
            requests.incrementAndGet();
            capturedHeaders.set(new LinkedHashMap<>(exchange.getRequestHeaders()));
            capturedBody.set(exchange.getRequestBody().readAllBytes());
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, 0);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write("{\"ignored\":true}".getBytes(StandardCharsets.UTF_8));
            }
        });
        server.start();
        return server.getAddress().getPort();
    }

    private static Map<String, String> signedHeaders() {
        Map<String, String> headers = new LinkedHashMap<>();
        headers.put("Content-Type", "application/json");
        headers.put("X-SharkPay-Signature", "t=1767312000,v1=" + "a".repeat(64));
        headers.put("X-SharkPay-Timestamp", "1767312000");
        headers.put("X-SharkPay-Delivery", "whd_0000000000000000000001");
        return headers;
    }

    @Test
    void twoHundredCountsAsDelivered() throws Exception {
        int port = startServer(200);
        WebhookSender sender = new JdkHttpClientWebhookSender();
        byte[] body = "{\"id\":\"x\"}".getBytes(StandardCharsets.UTF_8);

        WebhookSender.SendResult result = sender.send("http://127.0.0.1:" + port + "/hooks",
                body, signedHeaders());

        assertTrue(result.delivered());
        assertEquals(200, result.statusCode());
    }

    @Test
    void twoOhFourAlsoCountsAsDeliveredAndTheBodyIsIgnored() throws Exception {
        int port = startServer(204);
        WebhookSender.SendResult result = new JdkHttpClientWebhookSender()
                .send("http://127.0.0.1:" + port + "/hooks",
                        "{\"id\":\"x\"}".getBytes(StandardCharsets.UTF_8), signedHeaders());
        assertTrue(result.delivered());
        assertEquals(204, result.statusCode());
    }

    @Test
    void nonTwoXxIsARejectedAttempt() throws Exception {
        for (int status : new int[]{301, 400, 404, 422, 500, 503}) {
            int port = startServer(status);
            WebhookSender.SendResult result = new JdkHttpClientWebhookSender()
                    .send("http://127.0.0.1:" + port + "/hooks",
                            "{}".getBytes(StandardCharsets.UTF_8), signedHeaders());
            assertFalse(result.delivered(), status + " must not be delivered");
            assertEquals(status, result.statusCode());
            server.stop(0);
            server = null;
        }
    }

    @Test
    void transportFailuresHaveNoStatusAndNeverCountAsDelivered() {
        WebhookSender sender = new JdkHttpClientWebhookSender();
        // nothing listens here: connection refused
        WebhookSender.SendResult refused = sender.send("http://127.0.0.1:1/hooks",
                "{}".getBytes(StandardCharsets.UTF_8), signedHeaders());
        assertFalse(refused.delivered());
        assertEquals(WebhookSender.SendResult.NO_RESPONSE, refused.statusCode());
        // malformed URL
        WebhookSender.SendResult badUrl = sender.send("not a url",
                "{}".getBytes(StandardCharsets.UTF_8), signedHeaders());
        assertFalse(badUrl.delivered());
        assertEquals(WebhookSender.SendResult.NO_RESPONSE, badUrl.statusCode());
    }

    @Test
    void theRequestCarriesTheExactBodyAndSignatureHeaders() throws Exception {
        // the sender is transport-only (https is enforced at registration):
        // loopback http keeps the test local; the wire format is identical
        AtomicInteger seen = new AtomicInteger();
        AtomicReference<Map<String, List<String>>> headersRef = new AtomicReference<>();
        AtomicReference<byte[]> bodyRef = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/hooks", (HttpExchange exchange) -> {
            seen.incrementAndGet();
            headersRef.set(new LinkedHashMap<>(exchange.getRequestHeaders()));
            bodyRef.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(200, -1);
        });
        server.start();
        int port = server.getAddress().getPort();

        byte[] body = ("{\"id\":\"0192a7c4-6f3e-7b2a-9d1c-8e5f6a7b8c9d\","
                + "\"type\":\"payment.succeeded\"}").getBytes(StandardCharsets.UTF_8);
        new JdkHttpClientWebhookSender().send("http://127.0.0.1:" + port + "/hooks", body,
                signedHeaders());

        assertEquals(1, seen.get());
        assertEquals(new String(body, StandardCharsets.UTF_8),
                new String(bodyRef.get(), StandardCharsets.UTF_8));
        Map<String, List<String>> sent = headersRef.get();
        assertTrue(sent.getOrDefault("X-sharkpay-signature", List.of())
                .contains("t=1767312000,v1=" + "a".repeat(64)));
        assertTrue(sent.getOrDefault("X-sharkpay-timestamp", List.of())
                .contains("1767312000"));
        assertTrue(sent.getOrDefault("X-sharkpay-delivery", List.of())
                .contains("whd_0000000000000000000001"));
        assertTrue(sent.getOrDefault("Content-type", List.of()).contains("application/json"));
    }

    @Test
    void constructorRejectsNullTimeouts() {
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> new JdkHttpClientWebhookSender(null, java.time.Duration.ofSeconds(1)));
        org.junit.jupiter.api.Assertions.assertThrows(NullPointerException.class,
                () -> new JdkHttpClientWebhookSender(java.time.Duration.ofSeconds(1), null));
    }
}
