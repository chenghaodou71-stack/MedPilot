package com.medpilot.config;

import com.medpilot.auth.JwtAuthFilter;
import com.medpilot.auth.OidcJwtAuthenticationConverter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.security.web.csrf.CsrfTokenRequestAttributeHandler;
import org.springframework.security.oauth2.jwt.JwtDecoder;

/** JWT authentication and role-specific boundaries for operational APIs. */
@Configuration
public class SecurityConfig {

    private final JwtAuthFilter jwtAuthFilter;

    public SecurityConfig(JwtAuthFilter jwtAuthFilter) {
        this.jwtAuthFilter = jwtAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(
            HttpSecurity http,
            CookieCsrfTokenRepository csrfTokenRepository,
            ObjectProvider<JwtDecoder> oidcDecoderProvider,
            ObjectProvider<OidcJwtAuthenticationConverter> oidcConverterProvider) throws Exception {
        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/auth/login", "/api/auth/csrf").permitAll()
                .requestMatchers("/api/health", "/api/health/**").permitAll()
                .requestMatchers("/api/access/break-glass/**").hasRole("DOCTOR")
                .requestMatchers("/api/clinical-reviews/**").hasAnyRole("DOCTOR", "REVIEWER")
                // Governance reads are deliberately separated from clinical-record reads.
                // Keep the broad read role set for evidence review, then narrow sensitive
                // operational streams before the catch-all matcher below.
                .requestMatchers(HttpMethod.GET, "/api/governance/models/*/monitoring")
                        .hasAnyRole("ADMIN", "AUDITOR")
                .requestMatchers(HttpMethod.GET, "/api/governance/incidents/**")
                        .hasAnyRole("ADMIN", "AUDITOR", "REVIEWER", "DOCTOR")
                .requestMatchers(HttpMethod.GET, "/api/governance/**")
                        .hasAnyRole("ADMIN", "AUDITOR", "REVIEWER", "DOCTOR", "KNOWLEDGE_EDITOR")
                // Model and knowledge evidence is submitted by the owning operator,
                // while approval/review is performed by an independent clinical role.
                .requestMatchers(HttpMethod.POST, "/api/governance/models")
                        .hasAnyRole("ADMIN", "KNOWLEDGE_EDITOR")
                .requestMatchers(HttpMethod.POST, "/api/governance/evaluations/*/review",
                        "/api/governance/sources/*/review",
                        "/api/governance/models/*/approve",
                        "/api/governance/models/*/rollback")
                        .hasAnyRole("ADMIN", "REVIEWER", "DOCTOR")
                .requestMatchers(HttpMethod.POST, "/api/governance/models/*/freeze")
                        .hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/governance/evaluations")
                        .hasAnyRole("ADMIN", "REVIEWER", "DOCTOR")
                .requestMatchers(HttpMethod.POST, "/api/governance/sources")
                        .hasAnyRole("ADMIN", "KNOWLEDGE_EDITOR")
                .requestMatchers(HttpMethod.POST, "/api/governance/changes/*/approve",
                        "/api/governance/changes/*/reject")
                        .hasAnyRole("ADMIN", "REVIEWER")
                .requestMatchers(HttpMethod.POST, "/api/governance/changes/*/execute",
                        "/api/governance/changes/*/rollback")
                        .hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/governance/changes")
                        .hasAnyRole("ADMIN", "KNOWLEDGE_EDITOR", "REVIEWER", "DOCTOR")
                .requestMatchers(HttpMethod.POST, "/api/governance/red-team",
                        "/api/governance/rollback-drills")
                        .hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST, "/api/governance/monitoring")
                        .hasAnyRole("ADMIN", "AUDITOR")
                .requestMatchers(HttpMethod.POST, "/api/governance/incidents")
                        .hasAnyRole("ADMIN", "REVIEWER", "DOCTOR")
                .requestMatchers(HttpMethod.POST, "/api/governance/incidents/*/close")
                        .hasAnyRole("ADMIN", "REVIEWER")
                // Any future governance write defaults to administrator-only until its
                // ownership and independent-review rule is added explicitly above.
                .requestMatchers(HttpMethod.POST, "/api/governance/**")
                        .hasRole("ADMIN")
                .requestMatchers("/api/governance/**")
                        .hasRole("ADMIN")
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST,
                        "/api/knowledge/docs/*/review",
                        "/api/knowledge/versions/*/activate")
                        .hasAnyRole("ADMIN", "REVIEWER", "DOCTOR")
                .requestMatchers(HttpMethod.POST,
                        "/api/knowledge/ingest",
                        "/api/knowledge/upload",
                        "/api/knowledge/versions/build")
                        .hasAnyRole("ADMIN", "KNOWLEDGE_EDITOR")
                .requestMatchers(HttpMethod.DELETE, "/api/knowledge/**")
                        .hasAnyRole("ADMIN", "KNOWLEDGE_EDITOR")
                .requestMatchers(HttpMethod.GET, "/api/knowledge/**")
                        .hasAnyRole("ADMIN", "KNOWLEDGE_EDITOR", "REVIEWER", "DOCTOR", "AUDITOR")
                .requestMatchers(HttpMethod.GET, "/api/monitor/**", "/api/dashboard/**")
                        .hasAnyRole("ADMIN", "AUDITOR")
                .requestMatchers("/api/audit/**")
                        .hasAnyRole("ADMIN", "AUDITOR")
                .requestMatchers("/api/knowledge/**", "/api/monitor/**", "/api/dashboard/**")
                        .hasRole("ADMIN")
                .anyRequest().authenticated()
            )
            .exceptionHandling(exceptions -> exceptions
                .authenticationEntryPoint((request, response, exception) ->
                    response.setStatus(HttpServletResponse.SC_UNAUTHORIZED)))
            .httpBasic(AbstractHttpConfigurer::disable)
            .formLogin(AbstractHttpConfigurer::disable);

        JwtDecoder oidcDecoder = oidcDecoderProvider.getIfAvailable();
        if (oidcDecoder != null) {
            OidcJwtAuthenticationConverter oidcConverter = oidcConverterProvider.getIfAvailable();
            http.oauth2ResourceServer(oauth -> oauth
                    .authenticationEntryPoint((request, response, exception) ->
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED))
                    .jwt(jwt -> {
                        jwt.decoder(oidcDecoder);
                        if (oidcConverter != null) {
                            jwt.jwtAuthenticationConverter(oidcConverter);
                        }
                    }));
        }
        http.addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }

    @Bean
    public PasswordEncoder passwordEncoder() {
        return new BCryptPasswordEncoder();
    }

    @Bean
    public CookieCsrfTokenRepository csrfTokenRepository(
            @Value("${medpilot.jwt.cookie-secure:true}") boolean cookieSecure) {
        CookieCsrfTokenRepository repository = CookieCsrfTokenRepository.withHttpOnlyFalse();
        repository.setCookieCustomizer(cookie -> cookie
                .path("/")
                .sameSite("Strict")
                .secure(cookieSecure));
        return repository;
    }
}
