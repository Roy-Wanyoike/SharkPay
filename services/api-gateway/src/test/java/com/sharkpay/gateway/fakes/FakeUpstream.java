package com.sharkpay.gateway.fakes;

import com.sharkpay.gateway.ports.UpstreamPort;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.function.Function;

/**
 * In-memory {@link UpstreamPort} fake for passthrough tests: records every
 * forwarded request and answers with scripted responses (the real HTTP
 * routing adapter lands at integration — ADR 003 §3).
 */
public final class FakeUpstream implements UpstreamPort {

    /** One captured forward. */
    public record Forwarded(String method, String path, String body, UUID principalId) {
    }

    private final List<Forwarded> forwarded = new ArrayList<>();
    private Function<UpstreamRequest, UpstreamResponse> script =
            request -> new UpstreamResponse(200, "{\"fake\":true}");

    /** Scripts the response for every subsequent forward. */
    public void respondWith(Function<UpstreamRequest, UpstreamResponse> script) {
        this.script = script;
    }

    @Override
    public UpstreamResponse forward(UpstreamRequest request) {
        forwarded.add(new Forwarded(request.method(), request.path(), request.body(),
                request.principalId()));
        return script.apply(request);
    }

    public List<Forwarded> forwardedRequests() {
        return List.copyOf(forwarded);
    }

    public int forwardCount() {
        return forwarded.size();
    }
}
