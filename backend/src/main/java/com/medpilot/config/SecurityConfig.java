package com.medpilot.config;

import com.medpilot.auth.JwtAuthFilter;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
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
            CookieCsrfTokenRepository csrfTokenRepository) throws Exception {
        http
            .csrf(csrf -> csrf
                .csrfTokenRepository(csrfTokenRepository)
                .csrfTokenRequestHandler(new CsrfTokenRequestAttributeHandler()))
            .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth
                .dispatcherTypeMatchers(DispatcherType.ASYNC, DispatcherType.ERROR).permitAll()
                .requestMatchers("/api/auth/login", "/api/auth/csrf").permitAll()
                .requestMatchers("/api/health", "/api/health/**").permitAll()
                .requestMatchers("/api/admin/**").hasRole("ADMIN")
                .requestMatchers(HttpMethod.POST,
                        "/api/knowledge/docs/*/review",
                        "/api/knowledge/versions/*/activate")
                        .hasAnyRole("ADMIN", "REVIEWER", "DOCTOR")
                .requestMatchers(HttpMethod.POST,
                        "/api/knowledge/ingest",
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
            .formLogin(AbstractHttpConfigurer::disable)
            .addFilterBefore(jwtAuthFilter, UsernamePasswordAuthenticationFilter.class);
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
