package com.sharkpay.gateway.api;

import jakarta.servlet.http.HttpServletRequest;

import java.util.UUID;

/**
 * Resolves the authenticated caller attached by {@link ApiKeyAuthFilter}
 * (request attributes — no security-context dependency, so standalone
 * MockMvc tests with the filter behave exactly like production).
 */
public final class AuthenticatedRequest {

    private AuthenticatedRequest() {
    }

    /** The authenticated principal id (defense-in-depth: fails loudly if absent). */
    public static UUID principal(HttpServletRequest request) {
        Object attribute = request.getAttribute(ApiKeyAuthFilter.ATTR_PRINCIPAL_ID);
        if (attribute == null) {
            throw new IllegalStateException("request is not authenticated by the api key filter");
        }
        return UUID.fromString(attribute.toString());
    }

    /** The authenticated API key id. */
    public static String apiKeyId(HttpServletRequest request) {
        Object attribute = request.getAttribute(ApiKeyAuthFilter.ATTR_KEY_ID);
        if (attribute == null) {
            throw new IllegalStateException("request is not authenticated by the api key filter");
        }
        return attribute.toString();
    }
}
