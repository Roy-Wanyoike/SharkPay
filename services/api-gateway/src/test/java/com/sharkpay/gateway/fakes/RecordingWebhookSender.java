package com.sharkpay.gateway.fakes;

import com.sharkpay.gateway.ports.WebhookSender;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

/**
 * Recording webhook sender fake: captures every send (url, exact body
 * bytes, headers) and returns scripted results — the executable spec of
 * the wire contract the real JDK HttpClient adapter satisfies.
 */
public final class RecordingWebhookSender implements WebhookSender {

    /** One captured send. */
    public record Sent(String url, byte[] body, Map<String, String> headers) {

        public String bodyText() {
            return new String(body, java.nio.charset.StandardCharsets.UTF_8);
        }

        public String header(String name) {
            return headers.get(name);
        }
    }

    private final List<Sent> sent = new ArrayList<>();
    private Function<Sent, SendResult> script = request -> SendResult.delivered(200);

    /** Scripts the outcome for every subsequent send. */
    public void respondWith(Function<Sent, SendResult> script) {
        this.script = script;
    }

    /** Always delivers with the given status (2xx only makes sense). */
    public void alwaysDeliver(int status) {
        respondWith(request -> SendResult.delivered(status));
    }

    /** Always rejects with the given status (retry path). */
    public void alwaysReject(int status) {
        respondWith(request -> SendResult.rejected(status));
    }

    /** Always transport error (connect/timeout path). */
    public void alwaysTransportError() {
        respondWith(request -> SendResult.transportError());
    }

    @Override
    public SendResult send(String url, byte[] body, Map<String, String> headers) {
        Sent capture = new Sent(url, body, new LinkedHashMap<>(headers));
        sent.add(capture);
        return script.apply(capture);
    }

    /** All sends so far, in order. */
    public List<Sent> sends() {
        return List.copyOf(sent);
    }

    public int sendCount() {
        return sent.size();
    }
}
