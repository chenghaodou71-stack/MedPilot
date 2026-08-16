package com.medpilot.audit;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.UUID;

@Service
public class AuditLogService {
    private final AuditLogRepository repository;
    private final String ipSalt;

    public AuditLogService(AuditLogRepository repository,
                           @Value("${medpilot.audit.ip-salt:}") String ipSalt) {
        this.repository = repository;
        this.ipSalt = ipSalt == null ? "" : ipSalt;
    }

    public void record(HttpServletRequest request, int status, long durationMs) {
        try {
            Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
            String actor = authentication != null && authentication.isAuthenticated()
                    ? authentication.getName() : "anonymous";
            String role = authentication != null && authentication.isAuthenticated()
                    ? authentication.getAuthorities().stream().findFirst().map(Object::toString).orElse("") : "";
            String requestId = request.getHeader("X-Request-Id");
            if (requestId == null || !requestId.matches("[A-Za-z0-9._:-]{1,64}")) requestId = null;
            repository.save(new AuditLog(
                    UUID.randomUUID().toString(), actor, role,
                    request.getMethod().toUpperCase(Locale.ROOT),
                    normalizePath(request.getRequestURI()), status, status < 400,
                    requestId, hashIp(request.getRemoteAddr()), durationMs));
        } catch (RuntimeException ignored) {
            // Audit storage must never turn a completed medical request into a 500.
        }
    }

    private String normalizePath(String path) {
        if (path == null || path.isBlank()) return "/";
        String normalized = path.replaceAll("/[0-9]+(?=/|$)", "/{id}")
                .replaceAll("/[0-9a-fA-F]{8}-[0-9a-fA-F-]{27,}(?=/|$)", "/{id}");
        return normalized.length() <= 150 ? normalized : normalized.substring(0, 150);
    }

    private String hashIp(String ip) {
        if (ip == null || ip.isBlank()) return "";
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(
                    (ipSalt + ":" + ip).getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }
}
