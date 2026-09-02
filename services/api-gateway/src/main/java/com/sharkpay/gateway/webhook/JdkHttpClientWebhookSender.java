package com.sharkpay.gateway.webhook;

import com.sharkpay.gateway.ports.WebhookSender;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Production webhook sender: JDK HttpClient, no redirects, bounded
 * timeouts, response body discarded (webhooks.yaml: "any 2xx counts as
 * success; body is ignored"). Any non-2xx status or transport failure
 * (connect error, timeout, DNS) is a failed attempt — the dispatcher's
 * backoff policy decides what happens next.
 *
 * <p>This is the one real wire adapter in the service; tests exercise it
 * against a loopback {@code com.sun.net.httpserver} server (never an
 * external host). Endpoint URLs are https-enforced at registration
 * (domain invariant) — the sender is transport only and does not re-validate
 * the scheme.</p>
 */
public final class JdkHttpClientWebhookSender implements WebhookSender {

    private final HttpClient client;
    private final Duration requestTimeout;

    public JdkHttpClientWebhookSender(Duration connectTimeout, Duration requestTimeout) {
        Objects.requireNonNull(connectTimeout, "connectTimeout is required");
        this.requestTimeout = Objects.requireNonNull(requestTimeout, "requestTimeout is required");
        this.client = HttpClient.newBuilder()
                .connectTimeout(connectTimeout)
                .followRedirects(HttpClient.Redirect.NEVER)
                .build();
    }

    /** Defaults: 5 s connect, 10 s request (documented in application.yml). */
    public JdkHttpClientWebhookSender() {
        this(Duration.ofSeconds(5), Duration.ofSeconds(10));
    }

    @Override
    public SendResult send(String url, byte[] body, Map<String, String> headers) {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url))
                    .timeout(requestTimeout)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body));
            headers.forEach(builder::header);
            HttpResponse<Void> response = client.send(builder.build(),
                    HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            return status >= 200 && status < 300 ? SendResult.delivered(status)
                    : SendResult.rejected(status);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return SendResult.transportError();
        } catch (IOException | RuntimeException e) {
            // connect errors, timeouts, DNS failures, invalid URLs: failed attempt
            return SendResult.transportError();
        }
    }
}
