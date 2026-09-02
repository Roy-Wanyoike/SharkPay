package com.sharkpay.gateway.api;

import com.sharkpay.gateway.api.routing.RouteClass;
import com.sharkpay.gateway.api.routing.RouteTable;
import com.sharkpay.gateway.service.Ids;
import com.sharkpay.gateway.service.PassthroughService;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestMethod;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The /v1 passthrough skeleton: every authenticated request to a routed
 * public path (payments, payouts, transfers, wallets, fx) is forwarded to
 * the owning internal service through the {@code UpstreamPort}, with
 * gateway-level idempotency (scope: key + route class) and principal
 * propagation. The real HTTP adapter lands at integration time (ADR 003
 * §3); until then the production wiring fails fast — see
 * {@code config/IntegrationPendingUpstream}.
 *
 * <p>Unknown route classes never reach this controller: the auth filter
 * rejects them with 403 (fail-closed) before routing.</p>
 */
@RestController
public final class PassthroughController {

    private final PassthroughService passthrough;

    public PassthroughController(PassthroughService passthrough) {
        this.passthrough = passthrough;
    }

    /**
     * Forwards one request. The scope check, quota and route resolution
     * already happened in the auth filter; this is pure relay + idempotent
     * response caching.
     */
    @RequestMapping(value = {"/v1/payments/**", "/v1/payouts/**", "/v1/wallets/**",
            "/v1/transfers/**", "/v1/fx/**"},
            method = {RequestMethod.GET, RequestMethod.POST, RequestMethod.PUT,
                    RequestMethod.PATCH, RequestMethod.DELETE})
    public ResponseEntity<String> forward(HttpServletRequest request,
                                          @RequestBody(required = false) String body) {
        UUID principal = AuthenticatedRequest.principal(request);
        String path = request.getRequestURI()
                + (request.getQueryString() == null ? "" : "?" + request.getQueryString());
        RouteClass route = RouteTable.resolve(request.getRequestURI());
        PassthroughService.Result result = passthrough.forward(
                "PASSTHROUGH:" + route.name(), request.getMethod(), path, body,
                request.getHeader("Idempotency-Key"), principal);
        ResponseEntity.BodyBuilder builder = ResponseEntity.status(result.status())
                .header("X-Request-Id", Ids.requestId())
                .header("Content-Type", "application/json");
        if (result.replay()) {
            builder.header("X-Idempotent-Replay", "true");
        }
        return builder.body(result.body());
    }
}
