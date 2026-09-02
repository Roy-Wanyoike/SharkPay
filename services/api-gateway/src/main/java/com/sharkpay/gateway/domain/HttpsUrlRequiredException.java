package com.sharkpay.gateway.domain;

/** A webhook endpoint URL that is not https (webhooks.yaml 422 {@code http_url_required}). */
public final class HttpsUrlRequiredException extends GatewayDomainException {

    private final String url;

    public HttpsUrlRequiredException(String url) {
        super("webhook endpoint URLs must be https (TLS required): " + url);
        this.url = url;
    }

    public String url() {
        return url;
    }
}
