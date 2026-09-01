package com.sharkpay.payments.config;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * The production {@link com.sharkpay.payments.ports.PrincipalResolver}: the
 * Keycloak JWT subject of the current request. Resolves eagerly from the
 * security context and fails closed (500 path) when no JWT subject is
 * present — the standalone-MockMvc tests substitute the fixed fake, this
 * pins the real resolver.
 */
class JwtPrincipalResolverTest {

    private final JwtPrincipalResolver resolver = new JwtPrincipalResolver();

    @AfterEach
    void clearContext() {
        SecurityContextHolder.clearContext();
    }

    @Test
    void resolvesTheJwtSubjectAsThePrincipalId() {
        UUID subject = UUID.randomUUID();
        authenticate(jwt(subject.toString()));

        assertThat(resolver.resolve()).isEqualTo(subject);
    }

    @Test
    void failsClosedWithoutAuthentication() {
        SecurityContextHolder.clearContext();
        assertThatThrownBy(resolver::resolve)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no authenticated principal");
    }

    @Test
    void failsClosedForNonJwtPrincipals() {
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken("some-user", "credentials"));
        assertThatThrownBy(resolver::resolve)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("no authenticated principal");
    }

    @Test
    void aSubjectThatIsNotAUuidIsRejectedAsAValidationError() {
        authenticate(jwt("not-a-uuid"));
        // the subject column is a UUID (DATA-MODEL): a non-UUID subject is a
        // malformed token, surfaced as IllegalArgumentException (400 path)
        assertThatThrownBy(resolver::resolve)
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static Jwt jwt(String subject) {
        return Jwt.withTokenValue("test-token")
                .header("alg", "none")
                .subject(subject)
                .build();
    }

    private static void authenticate(Jwt jwt) {
        SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(jwt));
    }
}
