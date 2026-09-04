package com.medpilot.runtime;

import com.medpilot.security.DataEncryptionService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.RecordId;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.data.redis.connection.Limit;
import org.springframework.data.domain.Range;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Shared short-lived coordination state for multi-instance deployments.
 * Clinical messages and terminal traces remain in MySQL. Values written for
 * live traces are encrypted before they enter Redis; keys are HMAC-derived.
 */
@Service
public class RedisRuntimeState {

    private static final DefaultRedisScript<Long> RELEASE_SCRIPT = new DefaultRedisScript<>(
            "if redis.call('get', KEYS[1]) == ARGV[1] then "
                    + "return redis.call('del', KEYS[1]) else return 0 end", Long.class);
    private static final DefaultRedisScript<Long> LOGIN_FAILURE_SCRIPT = new DefaultRedisScript<>(
            "local count = redis.call('incr', KEYS[1]); "
                    + "if count == 1 then redis.call('pexpire', KEYS[1], ARGV[1]) end; "
                    + "if count >= tonumber(ARGV[2]) then "
                    + "redis.call('set', KEYS[2], '1', 'PX', ARGV[3]); return 1 end; return 0",
            Long.class);

    private final StringRedisTemplate redis;
    private final DataEncryptionService encryption;
    private final boolean enabled;
    private final boolean required;
    private final String keyPrefix;
    private final byte[] hmacSecret;

    public RedisRuntimeState(
            StringRedisTemplate redis,
            DataEncryptionService encryption,
            @Value("${medpilot.redis.enabled:true}") boolean enabled,
            @Value("${medpilot.redis.required:false}") boolean required,
            @Value("${medpilot.redis.key-prefix:medpilot}") String keyPrefix,
            @Value("${medpilot.redis.key-hmac-secret:}") String keyHmacSecret) {
        this.redis = redis;
        this.encryption = encryption;
        this.enabled = enabled;
        this.required = required;
        this.keyPrefix = normalizePrefix(keyPrefix);
        String secret = keyHmacSecret == null ? "" : keyHmacSecret.strip();
        this.hmacSecret = secret.getBytes(StandardCharsets.UTF_8);
    }

    /** True when this deployment explicitly asks for Redis coordination. */
    public boolean shouldUseSharedState() {
        return enabled || required;
    }

    /** True when the shared-state key material is usable. */
    public boolean isEnabled() {
        return shouldUseSharedState() && hmacSecret.length >= 16;
    }

    public boolean isRequired() {
        return required;
    }

    public boolean isAvailable() {
        if (!shouldUseSharedState()) return true;
        if (hmacSecret.length < 16) return false;
        try {
            return "PONG".equalsIgnoreCase(redis.getConnectionFactory().getConnection().ping());
        } catch (RuntimeException exception) {
            return false;
        }
    }

    public void requireAvailable() {
        if (shouldUseSharedState() && !isAvailable()) {
            throw new SharedRuntimeStateUnavailable("shared Redis state is unavailable");
        }
    }

    private void requireConfigured() {
        if (shouldUseSharedState() && !isEnabled()) {
            throw new SharedRuntimeStateUnavailable("Redis key HMAC secret is not configured");
        }
    }

    public String acquireLease(String namespace, String value, Duration ttl) {
        if (!shouldUseSharedState()) return null;
        requireConfigured();
        requireAvailable();
        String token = UUID.randomUUID().toString();
        try {
            Boolean acquired = redis.opsForValue().setIfAbsent(
                    key(namespace, value), token, ttl == null ? Duration.ofMinutes(5) : ttl);
            return Boolean.TRUE.equals(acquired) ? token : null;
        } catch (RuntimeException exception) {
            throw unavailable(exception, "Redis lease acquisition failed");
        }
    }

    public void releaseLease(String namespace, String value, String token) {
        if (!shouldUseSharedState() || token == null) return;
        requireConfigured();
        requireAvailable();
        try {
            redis.execute(RELEASE_SCRIPT, List.of(key(namespace, value)), token);
        } catch (RuntimeException exception) {
            throw unavailable(exception, "Redis lease release failed");
        }
    }

    public boolean isLoginBlocked(String clientIp, String username) {
        if (!shouldUseSharedState()) return false;
        requireConfigured();
        requireAvailable();
        try {
            Long ttl = redis.getExpire(key("login-lock", loginValue(clientIp, username)));
            return ttl != null && ttl > 0;
        } catch (RuntimeException exception) {
            throw unavailable(exception, "Redis login protection check failed");
        }
    }

    public void recordLoginFailure(
            String clientIp,
            String username,
            int maxFailures,
            Duration window,
            Duration lock) {
        if (!shouldUseSharedState()) return;
        requireConfigured();
        requireAvailable();
        String value = loginValue(clientIp, username);
        try {
            redis.execute(
                    LOGIN_FAILURE_SCRIPT,
                    List.of(key("login-count", value), key("login-lock", value)),
                    Long.toString(Math.max(1_000L, window.toMillis())),
                    Integer.toString(Math.max(1, maxFailures)),
                    Long.toString(Math.max(1_000L, lock.toMillis())));
        } catch (RuntimeException exception) {
            throw unavailable(exception, "Redis login protection update failed");
        }
    }

    public void clearLoginFailures(String clientIp, String username) {
        if (!shouldUseSharedState()) return;
        requireConfigured();
        requireAvailable();
        String value = loginValue(clientIp, username);
        try {
            redis.delete(List.of(key("login-count", value), key("login-lock", value)));
        } catch (RuntimeException exception) {
            throw unavailable(exception, "Redis login protection reset failed");
        }
    }

    /** Store encrypted live-trace JSON with a bounded retention period. */
    public void saveLiveTrace(String requestId, String encryptedSafeJson, Duration ttl) {
        if (!shouldUseSharedState()) return;
        requireConfigured();
        requireAvailable();
        try {
            String payload = encryption.encrypt(encryptedSafeJson);
            redis.opsForValue().set(key("live-trace", requestId), payload,
                    ttl == null ? Duration.ofMinutes(15) : ttl);
        } catch (RuntimeException exception) {
            throw unavailable(exception, "Redis live trace write failed");
        }
    }

    public String readLiveTrace(String requestId) {
        if (!shouldUseSharedState()) return null;
        requireConfigured();
        requireAvailable();
        try {
            String payload = redis.opsForValue().get(key("live-trace", requestId));
            return payload == null ? null : encryption.decrypt(payload);
        } catch (RuntimeException exception) {
            throw unavailable(exception, "Redis live trace read failed");
        }
    }

    /** Maintain a short-lived index of random request/trace identifiers. */
    public void indexLiveTrace(String identifier, long updatedAtEpochMs, Duration ttl) {
        if (!shouldUseSharedState()) return;
        requireConfigured();
        requireAvailable();
        try {
            String indexKey = key("live-trace-index", "all");
            redis.opsForZSet().add(indexKey, identifier, updatedAtEpochMs);
            redis.expire(indexKey, ttl == null ? Duration.ofMinutes(20) : ttl);
        } catch (RuntimeException exception) {
            throw unavailable(exception, "Redis live trace index update failed");
        }
    }

    public Set<String> liveTraceIdentifiers(int limit) {
        if (!shouldUseSharedState()) return Set.of();
        requireConfigured();
        requireAvailable();
        try {
            int bounded = Math.max(1, Math.min(limit, 512));
            Set<String> identifiers = redis.opsForZSet().reverseRange(
                    key("live-trace-index", "all"), 0, bounded - 1L);
            return identifiers == null ? Set.of() : identifiers;
        } catch (RuntimeException exception) {
            throw unavailable(exception, "Redis live trace index read failed");
        }
    }

    /** Append an encrypted snapshot event to the shared stream. */
    public String appendLiveTraceEvent(String kind, String snapshotJson, Duration ttl) {
        if (!shouldUseSharedState()) return null;
        requireConfigured();
        requireAvailable();
        try {
            String streamKey = key("live-trace-events", "all");
            String encrypted = encryption.encrypt(snapshotJson);
            RecordId id = redis.opsForStream().add(MapRecord.create(
                    streamKey, Map.of("kind", kind, "payload", encrypted)));
            redis.opsForStream().trim(streamKey, 512L, true);
            redis.expire(streamKey, ttl == null ? Duration.ofMinutes(20) : ttl);
            return id == null ? null : id.getValue();
        } catch (RuntimeException exception) {
            throw unavailable(exception, "Redis live trace event write failed");
        }
    }

    public List<SharedTraceEvent> readLiveTraceEvents(String afterId, int limit) {
        if (!shouldUseSharedState()) return List.of();
        requireConfigured();
        requireAvailable();
        try {
            String streamKey = key("live-trace-events", "all");
            String offset = afterId == null || afterId.isBlank() ? "0-0" : afterId;
            int bounded = Math.max(1, Math.min(limit, 128));
            List<MapRecord<String, Object, Object>> records = redis.opsForStream().range(
                    streamKey, Range.rightOpen(offset, "+"), Limit.limit().count(bounded));
            if (records == null || records.isEmpty()) return List.of();
            return records.stream()
                    .map(record -> new SharedTraceEvent(
                            record.getId().getValue(),
                            String.valueOf(record.getValue().getOrDefault("kind", "event")),
                            decrypt(String.valueOf(record.getValue().get("payload")))))
                    .toList();
        } catch (RuntimeException exception) {
            throw unavailable(exception, "Redis live trace event read failed");
        }
    }

    private String decrypt(String payload) {
        if (payload == null) {
            throw new SharedRuntimeStateUnavailable("Redis live trace event payload is missing");
        }
        return encryption.decrypt(payload);
    }

    private String key(String namespace, String value) {
        if (hmacSecret.length < 16) {
            throw new SharedRuntimeStateUnavailable("Redis key HMAC secret is not configured");
        }
        try {
            byte[] input = (namespace + "\u0000" + (value == null ? "" : value))
                    .getBytes(StandardCharsets.UTF_8);
            javax.crypto.Mac hmac = javax.crypto.Mac.getInstance("HmacSHA256");
            hmac.init(new javax.crypto.spec.SecretKeySpec(hmacSecret, "HmacSHA256"));
            byte[] mac = hmac.doFinal(input);
            return keyPrefix + ":" + namespace + ":" + HexFormat.of().formatHex(mac);
        } catch (NoSuchAlgorithmException | java.security.InvalidKeyException exception) {
            throw new SharedRuntimeStateUnavailable("HMAC-SHA256 is unavailable", exception);
        }
    }

    private String loginValue(String clientIp, String username) {
        return (clientIp == null ? "unknown" : clientIp) + "|"
                + (username == null ? "" : username.strip().toLowerCase(Locale.ROOT));
    }

    private SharedRuntimeStateUnavailable unavailable(RuntimeException cause, String message) {
        return new SharedRuntimeStateUnavailable(message, cause);
    }

    private static String normalizePrefix(String value) {
        String normalized = value == null || value.isBlank() ? "medpilot" : value.strip();
        if (!normalized.matches("[A-Za-z0-9_-]{1,64}")) {
            throw new IllegalStateException("medpilot.redis.key-prefix contains invalid characters");
        }
        return normalized;
    }

    public record SharedTraceEvent(String id, String kind, String snapshotJson) {
    }
}
