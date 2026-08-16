package com.medpilot.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginAttemptService {

    private static final int MAX_TRACKED_KEYS = 10_000;

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final int maxFailedAttempts;
    private final long failureWindowSeconds;
    private final long lockSeconds;
    private final Clock clock;

    @Autowired
    public LoginAttemptService(
            @Value("${medpilot.auth.max-failed-attempts:5}") int maxFailedAttempts,
            @Value("${medpilot.auth.failure-window-seconds:300}") long failureWindowSeconds,
            @Value("${medpilot.auth.lock-seconds:900}") long lockSeconds) {
        this(maxFailedAttempts, failureWindowSeconds, lockSeconds, Clock.systemUTC());
    }

    LoginAttemptService(int maxFailedAttempts, long failureWindowSeconds, long lockSeconds, Clock clock) {
        this.maxFailedAttempts = Math.max(1, maxFailedAttempts);
        this.failureWindowSeconds = Math.max(1, failureWindowSeconds);
        this.lockSeconds = Math.max(1, lockSeconds);
        this.clock = clock;
    }

    public boolean isBlocked(String clientIp, String username) {
        String key = key(clientIp, username);
        Attempt attempt = attempts.get(key);
        if (attempt == null) return false;
        Instant now = clock.instant();
        if (attempt.lockedUntil() != null && attempt.lockedUntil().isAfter(now)) return true;
        if (attempt.windowStarted().plusSeconds(failureWindowSeconds).isBefore(now)) {
            attempts.remove(key, attempt);
        }
        return false;
    }

    public void recordFailure(String clientIp, String username) {
        if (attempts.size() >= MAX_TRACKED_KEYS) evictExpired();
        Instant now = clock.instant();
        attempts.compute(key(clientIp, username), (ignored, previous) -> {
            Attempt current = previous;
            if (current == null || current.windowStarted().plusSeconds(failureWindowSeconds).isBefore(now)) {
                current = new Attempt(0, now, null);
            }
            int failures = current.failures() + 1;
            Instant lockedUntil = failures >= maxFailedAttempts ? now.plusSeconds(lockSeconds) : null;
            return new Attempt(failures, current.windowStarted(), lockedUntil);
        });
    }

    public void recordSuccess(String clientIp, String username) {
        attempts.remove(key(clientIp, username));
    }

    private void evictExpired() {
        Instant now = clock.instant();
        attempts.entrySet().removeIf(entry -> {
            Attempt value = entry.getValue();
            Instant expiry = value.lockedUntil() != null
                    ? value.lockedUntil()
                    : value.windowStarted().plusSeconds(failureWindowSeconds);
            return !expiry.isAfter(now);
        });
    }

    private static String key(String clientIp, String username) {
        String safeIp = clientIp == null ? "unknown" : clientIp;
        String safeUsername = username == null ? "" : username.trim().toLowerCase(Locale.ROOT);
        return safeIp + '|' + safeUsername;
    }

    private record Attempt(int failures, Instant windowStarted, Instant lockedUntil) {}
}
