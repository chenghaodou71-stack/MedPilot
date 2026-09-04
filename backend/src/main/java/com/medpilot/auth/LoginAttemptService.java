package com.medpilot.auth;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import com.medpilot.runtime.RedisRuntimeState;

import java.time.Clock;
import java.time.Instant;
import java.util.Locale;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class LoginAttemptService {

    private static final int DEFAULT_MAX_TRACKED_KEYS = 10_000;

    private final ConcurrentHashMap<String, Attempt> attempts = new ConcurrentHashMap<>();
    private final int maxFailedAttempts;
    private final long failureWindowSeconds;
    private final long lockSeconds;
    private final int maxTrackedKeys;
    private final Clock clock;
    private final RedisRuntimeState sharedState;

    @Autowired
    public LoginAttemptService(
            @Value("${medpilot.auth.max-failed-attempts:5}") int maxFailedAttempts,
            @Value("${medpilot.auth.failure-window-seconds:300}") long failureWindowSeconds,
            @Value("${medpilot.auth.lock-seconds:900}") long lockSeconds,
            RedisRuntimeState sharedState) {
        this(maxFailedAttempts, failureWindowSeconds, lockSeconds,
                DEFAULT_MAX_TRACKED_KEYS, Clock.systemUTC(), sharedState);
    }

    LoginAttemptService(int maxFailedAttempts, long failureWindowSeconds, long lockSeconds, Clock clock) {
        this(maxFailedAttempts, failureWindowSeconds, lockSeconds,
                DEFAULT_MAX_TRACKED_KEYS, clock, null);
    }

    LoginAttemptService(
            int maxFailedAttempts,
            long failureWindowSeconds,
            long lockSeconds,
            int maxTrackedKeys,
            Clock clock) {
        this(maxFailedAttempts, failureWindowSeconds, lockSeconds,
                maxTrackedKeys, clock, null);
    }

    LoginAttemptService(
            int maxFailedAttempts,
            long failureWindowSeconds,
            long lockSeconds,
            int maxTrackedKeys,
            Clock clock,
            RedisRuntimeState sharedState) {
        this.maxFailedAttempts = Math.max(1, maxFailedAttempts);
        this.failureWindowSeconds = Math.max(1, failureWindowSeconds);
        this.lockSeconds = Math.max(1, lockSeconds);
        this.maxTrackedKeys = Math.max(1, maxTrackedKeys);
        this.clock = clock;
        this.sharedState = sharedState;
    }

    public boolean isBlocked(String clientIp, String username) {
        if (sharedState != null && sharedState.shouldUseSharedState()) {
            return sharedState.isLoginBlocked(clientIp, username);
        }
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

    public synchronized void recordFailure(String clientIp, String username) {
        if (sharedState != null && sharedState.shouldUseSharedState()) {
            sharedState.recordLoginFailure(
                    clientIp,
                    username,
                    maxFailedAttempts,
                    java.time.Duration.ofSeconds(failureWindowSeconds),
                    java.time.Duration.ofSeconds(lockSeconds));
            return;
        }
        String attemptKey = key(clientIp, username);
        if (!attempts.containsKey(attemptKey) && attempts.size() >= maxTrackedKeys) {
            evictExpired();
            if (attempts.size() >= maxTrackedKeys) {
                attempts.entrySet().stream()
                        .min(java.util.Comparator.comparing(entry -> entry.getValue().windowStarted()))
                        .ifPresent(entry -> attempts.remove(entry.getKey(), entry.getValue()));
            }
        }
        Instant now = clock.instant();
        attempts.compute(attemptKey, (ignored, previous) -> {
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
        if (sharedState != null && sharedState.shouldUseSharedState()) {
            sharedState.clearLoginFailures(clientIp, username);
            return;
        }
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

    int trackedKeyCount() {
        return attempts.size();
    }

    private record Attempt(int failures, Instant windowStarted, Instant lockedUntil) {}
}
