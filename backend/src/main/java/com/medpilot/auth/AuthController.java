package com.medpilot.auth;

import com.medpilot.common.ApiResponse;
import com.medpilot.user.User;
import com.medpilot.user.UserRepository;
import jakarta.servlet.http.Cookie;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.core.Authentication;
import org.springframework.security.web.csrf.CsrfToken;
import org.springframework.security.web.csrf.CookieCsrfTokenRepository;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

@RestController
@RequestMapping("/api/auth")
public class AuthController {

    private final UserRepository users;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final LoginAttemptService loginAttempts;
    private final CookieCsrfTokenRepository csrfTokenRepository;
    private final boolean cookieSecure;
    private final boolean localPasswordLoginEnabled;
    private final int cookieMaxAgeSeconds;
    private final String dummyPasswordHash;

    public AuthController(
            UserRepository users,
            PasswordEncoder passwordEncoder,
            JwtService jwtService,
            LoginAttemptService loginAttempts,
            CookieCsrfTokenRepository csrfTokenRepository,
            @Value("${medpilot.jwt.cookie-secure:true}") boolean cookieSecure,
            @Value("${medpilot.identity.local-password-login-enabled:false}") boolean localPasswordLoginEnabled,
            @Value("${medpilot.jwt.expiration-ms:900000}") long expirationMs) {
        this.users = users;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.loginAttempts = loginAttempts;
        this.csrfTokenRepository = csrfTokenRepository;
        this.cookieSecure = cookieSecure;
        this.localPasswordLoginEnabled = localPasswordLoginEnabled;
        this.cookieMaxAgeSeconds = Math.max(1, Math.toIntExact(expirationMs / 1000));
        this.dummyPasswordHash = passwordEncoder.encode("medpilot-invalid-password-placeholder");
    }

    @PostMapping("/login")
    public ResponseEntity<ApiResponse<Map<String, Object>>> login(
            @Valid @RequestBody LoginRequest requestBody,
            HttpServletRequest request,
            HttpServletResponse response) {
        if (!localPasswordLoginEnabled) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(ApiResponse.fail("本地密码登录在此环境未启用，请使用医院统一身份认证"));
        }
        String clientIp = request.getRemoteAddr();
        if (loginAttempts.isBlocked(clientIp, requestBody.username())) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", "60")
                    .body(ApiResponse.fail("登录尝试过于频繁，请稍后再试"));
        }

        User user = users.findByUsername(requestBody.username()).orElse(null);
        String encodedPassword = user == null ? dummyPasswordHash : user.getPasswordHash();
        boolean passwordMatches = passwordEncoder.matches(requestBody.password(), encodedPassword);
        if (user == null || !user.isLoginEligibleAt(java.time.Instant.now())
                || !user.isLocalPasswordEnabled() || !passwordMatches) {
            loginAttempts.recordFailure(clientIp, requestBody.username());
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(ApiResponse.fail("用户名或密码错误"));
        }

        loginAttempts.recordSuccess(clientIp, requestBody.username());
        user.markAuthenticated(java.time.Instant.now());
        users.save(user);
        String token = jwtService.generate(
                user.getUsername(), user.getRole(), user.getTokenVersion());
        response.addCookie(authCookie(token, cookieMaxAgeSeconds));
        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "role", user.getRole().name(),
                "username", user.getUsername()
        )));
    }

    @GetMapping("/me")
    public ApiResponse<Map<String, Object>> me(Authentication authentication) {
        User user = users.findByUsername(authentication.getName())
                .filter(User::isActive)
                .orElseThrow(() -> new SecurityException("authenticated user not found"));
        return ApiResponse.ok(Map.of(
                "username", user.getUsername(),
                "role", user.getRole().name()));
    }

    @PostMapping("/logout")
    @Transactional
    public ApiResponse<Map<String, Object>> logout(
            Authentication authentication,
            HttpServletResponse response) {
        users.findByUsername(authentication.getName()).ifPresent(user -> {
            user.revokeTokens();
            users.save(user);
        });
        response.addCookie(authCookie("", 0));
        return ApiResponse.ok(Map.of("loggedOut", true));
    }

    @GetMapping("/csrf")
    public ApiResponse<Map<String, Object>> csrf(
            HttpServletRequest request,
            HttpServletResponse response) {
        CsrfToken csrfToken = csrfTokenRepository.generateToken(request);
        csrfTokenRepository.saveToken(csrfToken, request, response);
        return ApiResponse.ok(Map.of(
                "headerName", csrfToken.getHeaderName(),
                "parameterName", csrfToken.getParameterName(),
                "token", csrfToken.getToken()));
    }

    private Cookie authCookie(String value, int maxAge) {
        Cookie cookie = new Cookie(JwtAuthFilter.AUTH_COOKIE_NAME, value);
        cookie.setHttpOnly(true);
        cookie.setSecure(cookieSecure);
        cookie.setPath("/api");
        cookie.setMaxAge(maxAge);
        cookie.setAttribute("SameSite", "Strict");
        return cookie;
    }

    public record LoginRequest(
            @NotBlank @Size(max = 64) String username,
            @NotBlank @Size(max = 128) String password) {}
}
