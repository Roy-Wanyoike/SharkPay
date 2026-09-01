package com.sharkpay.payments.config;

import com.sharkpay.payments.ports.PrincipalResolver;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;

import java.util.UUID;

/**
 * Production {@link PrincipalResolver}: the Keycloak JWT subject of the
 * current request (resource-server security). Resolves eagerly at call time
 * from the security context.
 */
public final class JwtPrincipalResolver implements PrincipalResolver {

    @Override
    public UUID resolve() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof Jwt jwt
                && jwt.getSubject() != null) {
            return UUID.fromString(jwt.getSubject());
        }
        throw new IllegalStateException("no authenticated principal on the request"
                + " (expected Keycloak JWT subject)");
    }
}
