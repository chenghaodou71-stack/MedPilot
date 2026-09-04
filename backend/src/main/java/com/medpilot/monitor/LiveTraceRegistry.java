package com.medpilot.monitor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medpilot.runtime.RedisRuntimeState;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.HashMap;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

    /** Local cache backed by Redis when shared runtime state is enabled. */
@Service
public class LiveTraceRegistry {

    private static final int MAX_RECENT_TRACES = 256;

    private final ObjectMapper mapper;
    private final RedisRuntimeState sharedState;
    private final Map<String, MutableTrace> traces = new LinkedHashMap<>();
    private final Sinks.Many<Update> updates = Sinks.many().replay().limit(512);
    private final AtomicLong updateIds = new AtomicLong();

    public LiveTraceRegistry(ObjectMapper mapper) {
        this(mapper, null);
    }

    @Autowired
    public LiveTraceRegistry(ObjectMapper mapper, RedisRuntimeState sharedState) {
        this.mapper = mapper;
        this.sharedState = sharedState;
    }

    public synchronized Handle start(String sessionId, Long userId) {
        Handle handle = new Handle(UUID.randomUUID().toString(), sessionId, userId);
        MutableTrace trace = new MutableTrace(handle);
        traces.put(handle.requestId(), trace);
        trimRecent();
        emit("started", trace);
        persistShared(trace);
        return handle;
    }

    public synchronized void publish(Handle handle, String traceId, String rawEvent) {
        MutableTrace trace = require(handle);
        if (!"active".equals(trace.status)) return;
        trace.traceId = preferTraceId(trace.traceId, traceId);
        trace.events.add(readEvent(rawEvent));
        trace.updatedAt = Instant.now();
        emit("event", trace);
        persistShared(trace);
    }

    public synchronized void complete(Handle handle, String traceId) {
        terminate(handle, traceId, "completed", null);
    }

    public synchronized void fail(Handle handle, String traceId, String failureCode) {
        terminate(handle, traceId, "failed", normalizeFailure(failureCode));
    }

    public synchronized void cancel(Handle handle, String traceId) {
        terminate(handle, traceId, "cancelled", "CLIENT_CANCELLED");
    }

    public synchronized List<Snapshot> snapshots() {
        Map<String, Snapshot> merged = new HashMap<>();
        traces.values().stream()
                .map(MutableTrace::snapshot)
                .forEach(snapshot -> merged.put(snapshot.requestId(), snapshot));
        if (sharedState != null && sharedState.shouldUseSharedState()) {
            for (String identifier : sharedState.liveTraceIdentifiers(MAX_RECENT_TRACES * 2)) {
                String raw = sharedState.readLiveTrace(identifier);
                if (raw == null) continue;
                try {
                    Snapshot snapshot = mapper.readValue(raw, Snapshot.class);
                    Snapshot existing = merged.get(snapshot.requestId());
                    if (existing == null || snapshot.updatedAt().isAfter(existing.updatedAt())) {
                        merged.put(snapshot.requestId(), snapshot);
                    }
                } catch (JsonProcessingException exception) {
                    throw new IllegalStateException("stored live trace JSON is invalid", exception);
                }
            }
        }
        return merged.values().stream()
                .sorted(Comparator.comparing(Snapshot::startedAt).reversed())
                .toList();
    }

    public synchronized Optional<Snapshot> find(String traceIdOrRequestId) {
        if (traceIdOrRequestId == null) return Optional.empty();
        Optional<Snapshot> local = traces.values().stream()
                .filter(trace -> traceIdOrRequestId.equals(trace.handle.requestId())
                        || traceIdOrRequestId.equals(trace.traceId))
                .findFirst()
                .map(MutableTrace::snapshot);
        if (local.isPresent() || sharedState == null || !sharedState.shouldUseSharedState()) return local;
        String raw = sharedState.readLiveTrace(traceIdOrRequestId);
        if (raw == null) return Optional.empty();
        try {
            return Optional.of(mapper.readValue(raw, Snapshot.class));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored live trace JSON is invalid", exception);
        }
    }

    /** Every subscriber first receives a complete current snapshot, then live deltas. */
    public Flux<Update> stream() {
        if (sharedState == null || !sharedState.shouldUseSharedState()) {
            return Flux.defer(() -> {
                Update initial = initialUpdate();
                return Flux.concat(
                        Mono.just(initial),
                        updates.asFlux().filter(update -> update.id() > initial.id()));
            });
        }
        return Flux.defer(() -> {
            Update initial = initialUpdate();
            Map<String, Instant> seen = new HashMap<>();
            if (initial.traces() != null) {
                initial.traces().forEach(snapshot -> seen.put(snapshot.requestId(), snapshot.updatedAt()));
            }
            AtomicReference<String> cursor = new AtomicReference<>("0-0");
            Flux<Update> shared = Flux.interval(java.time.Duration.ZERO, java.time.Duration.ofMillis(500))
                    .publishOn(Schedulers.boundedElastic())
                    .flatMapIterable(ignored -> {
                        List<RedisRuntimeState.SharedTraceEvent> events =
                                sharedState.readLiveTraceEvents(cursor.get(), 128);
                        if (!events.isEmpty()) cursor.set(events.get(events.size() - 1).id());
                        return events;
                    })
                    .map(event -> parseSharedUpdate(event, seen))
                    .filter(java.util.Objects::nonNull);
            return Flux.concat(Mono.just(initial), shared);
        });
    }

    private Update parseSharedUpdate(
            RedisRuntimeState.SharedTraceEvent event,
            Map<String, Instant> seen) {
        try {
            Snapshot snapshot = mapper.readValue(event.snapshotJson(), Snapshot.class);
            Instant previous = seen.get(snapshot.requestId());
            if (previous != null && !snapshot.updatedAt().isAfter(previous)) return null;
            seen.put(snapshot.requestId(), snapshot.updatedAt());
            return new Update(updateIds.incrementAndGet(), event.kind(), List.of(), snapshot);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("stored live trace event JSON is invalid", exception);
        }
    }

    private void terminate(
            Handle handle,
            String traceId,
            String status,
            String failureCode) {
        MutableTrace trace = require(handle);
        if (!"active".equals(trace.status)) return;
        trace.traceId = preferTraceId(trace.traceId, traceId);
        trace.status = status;
        trace.failureCode = failureCode;
        trace.updatedAt = Instant.now();
        trace.endedAt = trace.updatedAt;
        emit(status, trace);
        persistShared(trace);
        trimRecent();
    }

    private MutableTrace require(Handle handle) {
        MutableTrace trace = handle == null ? null : traces.get(handle.requestId());
        if (trace == null && handle != null && sharedState != null
                && sharedState.shouldUseSharedState()) {
            String raw = sharedState.readLiveTrace(handle.requestId());
            if (raw != null) {
                try {
                    trace = MutableTrace.fromSnapshot(mapper.readValue(raw, Snapshot.class), handle);
                    traces.put(handle.requestId(), trace);
                } catch (JsonProcessingException exception) {
                    throw new IllegalStateException("stored live trace JSON is invalid", exception);
                }
            }
        }
        if (trace == null) throw new IllegalArgumentException("unknown live trace handle");
        return trace;
    }

    private JsonNode readEvent(String rawEvent) {
        try {
            JsonNode event = mapper.readTree(rawEvent);
            if (event == null || !event.isObject()) {
                throw new IllegalArgumentException("live trace event must be an object");
            }
            return event;
        } catch (JsonProcessingException exception) {
            throw new IllegalArgumentException("live trace event is invalid JSON", exception);
        }
    }

    private void emit(String kind, MutableTrace trace) {
        updates.tryEmitNext(new Update(
                updateIds.incrementAndGet(), kind, List.of(), trace.snapshot()));
    }

    private synchronized Update initialUpdate() {
        return new Update(
                updateIds.incrementAndGet(), "snapshot", snapshots(), null);
    }

    private void trimRecent() {
        while (traces.size() > MAX_RECENT_TRACES) {
            String removable = traces.entrySet().stream()
                    .filter(entry -> !"active".equals(entry.getValue().status))
                    .map(Map.Entry::getKey)
                    .findFirst()
                    .orElse(null);
            if (removable == null) return;
            traces.remove(removable);
        }
    }

    private void persistShared(MutableTrace trace) {
        if (sharedState == null || !sharedState.shouldUseSharedState()) return;
        try {
            String json = mapper.writeValueAsString(trace.snapshot());
            sharedState.saveLiveTrace(trace.handle.requestId(), json,
                    java.time.Duration.ofMinutes(15));
            sharedState.indexLiveTrace(trace.handle.requestId(),
                    trace.updatedAt.toEpochMilli(), java.time.Duration.ofMinutes(20));
            if (trace.traceId != null && !trace.traceId.isBlank()) {
                sharedState.saveLiveTrace(trace.traceId, json,
                        java.time.Duration.ofMinutes(15));
                sharedState.indexLiveTrace(trace.traceId,
                        trace.updatedAt.toEpochMilli(), java.time.Duration.ofMinutes(20));
            }
            sharedState.appendLiveTraceEvent(trace.status, json,
                    java.time.Duration.ofMinutes(20));
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("live trace snapshot serialization failed", exception);
        }
    }

    private static String preferTraceId(String current, String candidate) {
        return candidate == null || candidate.isBlank() ? current : candidate;
    }

    private static String normalizeFailure(String failureCode) {
        return failureCode == null || failureCode.isBlank()
                ? "UPSTREAM_STREAM_ERROR"
                : failureCode;
    }

    public record Handle(String requestId, String sessionId, Long userId) {
    }

    public record Snapshot(
            String requestId,
            String traceId,
            String sessionId,
            Long userId,
            String status,
            String failureCode,
            List<JsonNode> events,
            Instant startedAt,
            Instant updatedAt,
            Instant endedAt) {
    }

    public record Update(long id, String kind, List<Snapshot> traces, Snapshot trace) {
    }

    private static final class MutableTrace {
        private final Handle handle;
        private final Instant startedAt = Instant.now();
        private final List<JsonNode> events = new ArrayList<>();
        private String traceId;
        private String status = "active";
        private String failureCode;
        private Instant updatedAt = startedAt;
        private Instant endedAt;

        private MutableTrace(Handle handle) {
            this.handle = handle;
        }

        private static MutableTrace fromSnapshot(Snapshot snapshot, Handle handle) {
            MutableTrace trace = new MutableTrace(handle);
            trace.traceId = snapshot.traceId();
            trace.status = snapshot.status();
            trace.failureCode = snapshot.failureCode();
            trace.events.addAll(snapshot.events() == null ? List.of() : snapshot.events());
            trace.updatedAt = snapshot.updatedAt() == null ? trace.startedAt : snapshot.updatedAt();
            trace.endedAt = snapshot.endedAt();
            return trace;
        }

        private Snapshot snapshot() {
            return new Snapshot(
                    handle.requestId(),
                    traceId,
                    handle.sessionId(),
                    handle.userId(),
                    status,
                    failureCode,
                    events.stream().map(event -> event.<JsonNode>deepCopy()).toList(),
                    startedAt,
                    updatedAt,
                    endedAt);
        }
    }
}
