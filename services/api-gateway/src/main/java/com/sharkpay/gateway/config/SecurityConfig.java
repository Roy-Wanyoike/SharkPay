package com.sharkpay.gateway.config;

import com.sharkpay.gateway.api.ApiKeyAuthFilter;
import com.sharkpay.gateway.ports.ApiKeyRepository;
import com.sharkpay.gateway.ports.QuotaStore;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.access.intercept.AuthorizationFilter;

import java.time.Clock;

/**
 * Gateway security: the public API surface ({@code /v1/**},
 * {@code /sandbox/**}) is guarded by the {@link ApiKeyAuthFilter} —
 * API keys, not user JWTs (docs/BACKEND-DESIGN.md §10) — inserted before
 * authorization. The private surface ({@code /internal/**}) requires a
 * Keycloak service JWT like every other internal service; actuator health
 * stays open for Kubernetes. Tests bypass Spring Security entirely
 * (standalone MockMvc with the filter attached directly).
 */
@Configuration(proxyBeanMethods = false)
@EnableWebSecurity
public class SecurityConfig {

    @Bean
    public SecurityFilterChain securityFilterChain(HttpSecurity http, ApiKeyRepository keys,
                                                   QuotaStore quotas, Clock clock)
            throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health/**").permitAll()
                        .requestMatchers("/v1/**", "/sandbox/**").permitAll()
                        .anyRequest().authenticated())
                .addFilterBefore(new ApiKeyAuthFilter(keys, quotas, clock),
                        AuthorizationFilter.class)
                .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()));
        return http.build();
    }
}
