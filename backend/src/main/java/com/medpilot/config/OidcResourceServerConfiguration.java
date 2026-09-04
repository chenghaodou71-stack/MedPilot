package com.medpilot.config;

import com.medpilot.auth.OidcJwtAuthenticationConverter;
import com.medpilot.user.UserRepository;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtDecoders;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;

/**
 * Optional OIDC resource-server boundary. It is deliberately fail-closed:
 * enabling it without issuer/audience configuration prevents application
 * startup instead of silently accepting unverifiable bearer tokens.
 */
@Configuration
@ConditionalOnProperty(
        prefix = "medpilot.identity.oidc",
        name = "enabled",
        havingValue = "true")
public class OidcResourceServerConfiguration {

    @Bean
    public JwtDecoder oidcJwtDecoder(
            @Value("${medpilot.identity.oidc.issuer-uri:}") String issuerUri,
            @Value("${medpilot.identity.oidc.jwk-set-uri:}") String jwkSetUri,
            @Value("${medpilot.identity.oidc.audience:}") String audience,
            @Value("${medpilot.identity.oidc.allow-insecure-http:false}") boolean allowInsecureHttp) {
        String issuer = requiredUri(issuerUri, "OIDC issuer URI", allowInsecureHttp);
        String expectedAudience = required(audience, "OIDC audience");
        JwtDecoder decoder;
        if (jwkSetUri != null && !jwkSetUri.isBlank()) {
            String jwk = requiredUri(jwkSetUri, "OIDC JWK set URI", allowInsecureHttp);
            NimbusJwtDecoder nimbus = NimbusJwtDecoder.withJwkSetUri(jwk).build();
            nimbus.setJwtValidator(validators(issuer, expectedAudience));
            decoder = nimbus;
        } else {
            JwtDecoder discovered = JwtDecoders.fromIssuerLocation(issuer);
            if (!(discovered instanceof NimbusJwtDecoder nimbus)) {
                throw new IllegalStateException("OIDC issuer did not provide a Nimbus JWT decoder");
            }
            nimbus.setJwtValidator(validators(issuer, expectedAudience));
            decoder = nimbus;
        }
        return decoder;
    }

    @Bean
    public OidcJwtAuthenticationConverter oidcJwtAuthenticationConverter(
            UserRepository users,
            @Value("${medpilot.identity.oidc.mfa-claim:acr}") String mfaClaim,
            @Value("${medpilot.identity.oidc.required-mfa-assurance-level:2}") int requiredMfa) {
        return new OidcJwtAuthenticationConverter(users, mfaClaim, requiredMfa);
    }

    private static OAuth2TokenValidator<Jwt> validators(String issuer, String audience) {
        List<OAuth2TokenValidator<Jwt>> validators = new ArrayList<>();
        validators.add(JwtValidators.createDefaultWithIssuer(issuer));
        validators.add(jwt -> jwt.getAudience() != null && jwt.getAudience().contains(audience)
                ? OAuth2TokenValidatorResult.success()
                : OAuth2TokenValidatorResult.failure(
                        new org.springframework.security.oauth2.core.OAuth2Error(
                                "invalid_token", "required audience is missing", null)));
        return new DelegatingOAuth2TokenValidator<>(validators);
    }

    private static String required(String value, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty()) throw new IllegalStateException(field + " is required when OIDC is enabled");
        return normalized;
    }

    private static String requiredUri(String value, String field, boolean allowInsecureHttp) {
        String normalized = required(value, field);
        try {
            URI uri = URI.create(normalized);
            String scheme = uri.getScheme();
            if (uri.getHost() == null || scheme == null
                    || (!allowInsecureHttp && !"https".equalsIgnoreCase(scheme))) {
                throw new IllegalStateException(field + " must be an HTTPS URI");
            }
        } catch (IllegalArgumentException exception) {
            throw new IllegalStateException(field + " is invalid", exception);
        }
        return normalized;
    }
}
