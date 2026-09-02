package com.sharkpay.gateway.api;

import com.sharkpay.gateway.api.dto.ErrorEnvelope;
import com.sharkpay.gateway.api.routing.RouteClass;
import com.sharkpay.gateway.api.routing.RouteTable;
import com.sharkpay.gateway.domain.ApiKey;
import com.sharkpay.gateway.domain.ApiKeyStatus;
import com.sharkpay.gateway.domain.QuotaDecision;
import com.sharkpay.gateway.domain.Scope;
import com.sharkpay.gateway.ports.ApiKeyRepository;
import com.sharkpay.gateway.ports.QuotaStore;
import com.sharkpay.gateway.service.Ids;
import com.sharkpay.gateway.service.KeyHasher;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.filter.OncePerRequestFilter;
import tools.jackson.databind.json.JsonMapper;

import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * API key authentication filter — the gateway's front door.
 *
 * <p>Order of checks (all fail-closed):</p>
 * <ol>
 *   <li>{@code Authorization: Bearer sk_...} → SHA-256 → repository lookup
 *       by hash → constant-time digest comparison
 *       ({@link KeyHasher#matchesConstantTime}); missing/unknown/revoked/
 *       grace-expired secrets are 401;</li>
 *   <li>route class from the {@link RouteTable} — an unknown route class is
 *       a 403 (never an open route);</li>
 *   <li>the route class's required scope for the HTTP method — missing
 *       scope (or a method with no satisfiable scope, e.g. POST on
 *       /v1/wallets) is a 403 with {@code details.required_scope};</li>
 *   <li>per-key rpm/monthly quota — exceeded is 429 {@code quota_exceeded}
 *       with a {@code Retry-After} header (seconds until the window
 *       resets).</li>
 * </ol>
 *
 * <p>On success the caller's key id and principal id are attached as
 * request attributes for the controllers. {@code /internal/**} and
 * {@code /actuator/**} bypass this filter (they are private-surface paths
 * guarded by Spring Security).</p>
 */
public final class ApiKeyAuthFilter extends OncePerRequestFilter {

    /** Request attribute carrying the authenticated key id. */
    public static final String ATTR_KEY_ID = "gateway.apiKeyId";

    /** Request attribute carrying the authenticated principal id (UUID string). */
    public static final String ATTR_PRINCIPAL_ID = "gateway.principalId";

    private static final String BEARER_PREFIX = "bearer ";

    private static final JsonMapper ERROR_MAPPER = JsonMapper.builder().build();

    private final ApiKeyRepository keys;
    private final QuotaStore quotas;
    private final Clock clock;

    public ApiKeyAuthFilter(ApiKeyRepository keys, QuotaStore quotas, Clock clock) {
        this.keys = keys;
        this.quotas = quotas;
        this.clock = clock;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        String path = request.getRequestURI();
        if (RouteTable.isNonApiSurface(path)) {
            chain.doFilter(request, response);
            return;
        }

        ApiKey key = authenticate(request, response, clock.instant());
        if (key == null) {
            return;
        }

        RouteClass route = RouteTable.resolve(path);
        Optional<Scope> required = route.requiredScope(request.getMethod());
        if (route == RouteClass.UNKNOWN || required.isEmpty()) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "forbidden",
                    "No route class (or satisfiable scope for this method) is registered"
                            + " for this path — fail-closed.",
                    details -> details.put("path", path));
            return;
        }
        if (!key.hasScope(required.get())) {
            reject(response, HttpServletResponse.SC_FORBIDDEN, "forbidden",
                    "Key lacks required scope " + required.get().wireName() + ".",
                    details -> details.put("required_scope", required.get().wireName()));
            return;
        }

        QuotaDecision decision = quotas.checkAndConsume(key.id(), key.rpmLimit(),
                key.monthlyLimit(), clock.instant());
        if (!decision.allowed()) {
            long retryAfter = decision.retryAfter().orElse(1L);
            response.setHeader("Retry-After", String.valueOf(retryAfter));
            reject(response, 429, "quota_exceeded",
                    decision.monthly()
                            ? "Monthly request quota exceeded."
                            : "Requests-per-minute quota exceeded.",
                    details -> {
                        details.put("retry_after_seconds", retryAfter);
                        details.put("window", decision.monthly() ? "monthly" : "minute");
                    });
            return;
        }

        request.setAttribute(ATTR_KEY_ID, key.id());
        request.setAttribute(ATTR_PRINCIPAL_ID, key.principalId().toString());
        chain.doFilter(request, response);
    }

    /**
     * Resolves the bearer secret to an authenticating key, writing a 401
     * envelope and returning {@code null} on any failure.
     */
    private ApiKey authenticate(HttpServletRequest request, HttpServletResponse response,
                                Instant now) throws IOException {
        String authorization = request.getHeader("Authorization");
        if (authorization == null || authorization.isBlank()) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized",
                    "Missing API key.", null);
            return null;
        }
        String scheme = authorization.trim().toLowerCase(java.util.Locale.ROOT);
        if (!scheme.startsWith(BEARER_PREFIX)) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized",
                    "Authorization must be a Bearer API key.", null);
            return null;
        }
        String secret = authorization.trim().substring(BEARER_PREFIX.length()).trim();
        if (!secret.startsWith("sk_") || secret.length() < ApiKey.MIN_SECRET_LENGTH) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized",
                    "Invalid API key.", null);
            return null;
        }
        String hash = KeyHasher.sha256Hex(secret);
        ApiKey key = keys.findByHash(hash).orElse(null);
        if (key == null || !KeyHasher.matchesConstantTime(secret, key.secretHash())) {
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized",
                    "Invalid API key.", null);
            return null;
        }
        if (!key.authenticatesAt(now)) {
            String reason = key.status() == ApiKeyStatus.REVOKED
                    ? "API key is revoked."
                    : "API key rotation grace window has expired.";
            reject(response, HttpServletResponse.SC_UNAUTHORIZED, "unauthorized", reason, null);
            return null;
        }
        return key;
    }

    private interface DetailBuilder {
        void accept(Map<String, Object> details);
    }

    private static void reject(HttpServletResponse response, int status, String code,
                               String message, DetailBuilder details) throws IOException {
        Map<String, Object> detailMap = null;
        if (details != null) {
            Map<String, Object> mutable = new LinkedHashMap<>();
            details.accept(mutable);
            detailMap = mutable;
        }
        ErrorEnvelope envelope = new ErrorEnvelope(
                new ErrorEnvelope.Error(code, message, Ids.requestId(), detailMap));
        response.setStatus(status);
        response.setContentType("application/json");
        response.setCharacterEncoding("UTF-8");
        ERROR_MAPPER.writeValue(response.getWriter(), envelope);
    }
}
