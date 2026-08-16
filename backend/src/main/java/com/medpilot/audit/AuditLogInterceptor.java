package com.medpilot.audit;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.lang.NonNull;
import org.springframework.web.servlet.HandlerInterceptor;

public class AuditLogInterceptor implements HandlerInterceptor {
    private static final String START = AuditLogInterceptor.class.getName() + ".start";
    private final AuditLogService service;

    public AuditLogInterceptor(AuditLogService service) { this.service = service; }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        request.setAttribute(START, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler, Exception exception) {
        String path = request.getRequestURI();
        if (!path.startsWith("/api/") || path.startsWith("/api/health")
                || "/api/auth/csrf".equals(path)) return;
        Object start = request.getAttribute(START);
        long duration = start instanceof Long value
                ? (System.nanoTime() - value) / 1_000_000L : 0L;
        service.record(request, response.getStatus(), duration);
    }
}
