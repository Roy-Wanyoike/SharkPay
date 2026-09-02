package com.sharkpay.gateway.ports;

/**
 * Outbound webhook sender port: POSTs one signed delivery to the endpoint.
 * The dispatcher owns the wire contract (signature headers); the sender
 * only transports bytes. Production adapter: JDK HttpClient
 * ({@code JdkHttpClientWebhookSender}); tests use the recording fake and a
 * loopback {@code com.sun.net.httpserver} server for the real adapter's
 * wire tests.
 */
public interface WebhookSender {

    /**
     * Sends the body with the given headers.
     *
     * @param url     https endpoint URL (validated at registration)
     * @param body    exact payload bytes (the signed bytes)
     * @param headers signature + content-type headers
     * @return the outcome: 2xx = delivered; anything else (or a transport
     *         failure/timeout) = failed attempt with the status if any
     */
    SendResult send(String url, byte[] body, java.util.Map<String, String> headers);

    /** Delivery outcome of one send attempt. */
    record SendResult(boolean delivered, int statusCode) {

        public static final int NO_RESPONSE = -1;

        /** A 2xx response (webhooks.yaml: any 2xx counts, body ignored). */
        public static SendResult delivered(int statusCode) {
            return new SendResult(true, statusCode);
        }

        /** A non-2xx response. */
        public static SendResult rejected(int statusCode) {
            return new SendResult(false, statusCode);
        }

        /** Transport failure: connect error, timeout, DNS — no response. */
        public static SendResult transportError() {
            return new SendResult(false, NO_RESPONSE);
        }
    }
}
