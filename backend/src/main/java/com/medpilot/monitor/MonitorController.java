package com.medpilot.monitor;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medpilot.common.ApiResponse;
import com.medpilot.config.AiServiceClientConfig;
import com.medpilot.consult.ConsultationTrace;
import com.medpilot.consult.ConsultationTraceRepository;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Lazy;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

@RestController
@RequestMapping("/api/monitor")
public class MonitorController {

    private final WebClient aiClient;
    private final ConsultationTraceRepository traceRepository;
    private final ObjectMapper mapper;
    private final LiveTraceRegistry liveTraces;

    @Autowired
    public MonitorController(
            @Qualifier(AiServiceClientConfig.CLIENT_BEAN) @Lazy WebClient aiClient,
            ConsultationTraceRepository traces,
            ObjectMapper mapper,
            LiveTraceRegistry liveTraces) {
        this.aiClient = aiClient;
        this.traceRepository = traces;
        this.mapper = mapper;
        this.liveTraces = liveTraces;
    }

    /** Backward-compatible constructor for isolated client configuration tests. */
    public MonitorController(
            WebClient aiClient,
            ConsultationTraceRepository traces,
            ObjectMapper mapper) {
        this(aiClient, traces, mapper, new LiveTraceRegistry(mapper));
    }

    @GetMapping("/health")
    public Mono<ResponseEntity<ApiResponse<Object>>> health() {
        return aiClient.get()
                .uri("/monitor/health")
                .exchangeToMono(response -> response.bodyToMono(Object.class)
                        .defaultIfEmpty(Map.of())
                        .map(body -> response.statusCode().is2xxSuccessful()
                                ? ResponseEntity.ok(ApiResponse.ok(body))
                                : ResponseEntity.status(response.statusCode())
                                        .body(ApiResponse.fail("AI health check failed", body))))
                .onErrorResume(error ->
                        Mono.just(ResponseEntity.status(HttpStatus.BAD_GATEWAY)
                                .body(ApiResponse.fail("ai-service unreachable"))));
    }

    @GetMapping("/trace/{traceId}")
    public ResponseEntity<ApiResponse<Object>> trace(@PathVariable String traceId) {
        ConsultationTrace trace = traceRepository.findByTraceId(traceId).orElse(null);
        if (trace == null) {
            LiveTraceRegistry.Snapshot live = liveTraces.find(traceId).orElse(null);
            if (live != null) {
                return ResponseEntity.ok(ApiResponse.ok(live));
            }
            return ResponseEntity.status(HttpStatus.NOT_FOUND)
                    .body(ApiResponse.fail("Trace not found"));
        }
        JsonNode events = readJson(trace.getEventsJson());
        try {
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("traceId", trace.getTraceId());
            data.put("sessionId", trace.getSessionId());
            data.put("events", events);
            data.put("citations", mapper.readTree(trace.getCitationsJson()));
            data.put("terminalPhase", trace.getTerminalPhase());
            data.put("status", statusOf(trace, events));
            data.put("failureCode", failureCodeOf(trace, events));
            data.put("totalDurationMs", totalDuration(trace, events));
            data.put("nodeStats", nodeStats(events));
            data.put("followupPending", trace.isFollowupPending());
            data.put("createdAt", trace.getCreatedAt().toString());
            return ResponseEntity.ok(ApiResponse.ok(data));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("stored trace JSON is invalid", ex);
        }
    }

    /** Current active and recently terminated in-process traces. */
    @GetMapping("/live")
    public ApiResponse<List<LiveTraceRegistry.Snapshot>> live() {
        return ApiResponse.ok(liveTraces.snapshots());
    }

    /** Snapshot-first SSE stream; reconnecting clients immediately recover current state. */
    @GetMapping(value = "/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<LiveTraceRegistry.Update>> events() {
        return liveTraces.stream().map(update -> ServerSentEvent.builder(update)
                .id(Long.toString(update.id()))
                .event(update.kind())
                .build());
    }

    /** Paginated, read-only trace summaries for administrators and auditors. */
    @GetMapping("/traces")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listTraces(
            @RequestParam(required = false) String terminalPhase,
            @RequestParam(required = false) String phase,
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String failureCode,
            @RequestParam(required = false) String errorCode,
            @RequestParam(required = false) String error,
            @RequestParam(required = false) Boolean timeout,
            @RequestParam(required = false) Boolean timeoutOnly,
            @RequestParam(required = false) String node,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) Instant startTime,
            @RequestParam(required = false) Instant endTime,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        validatePage(page, size);
        String requestedPhase = firstNonBlank(terminalPhase, phase);
        String requestedFailure = firstNonBlank(failureCode, errorCode, error);
        boolean timeoutFilter = Boolean.TRUE.equals(timeout) || Boolean.TRUE.equals(timeoutOnly);

        List<ParsedTrace> filtered = traceRepository.findAll().stream()
                .sorted(Comparator.comparing(ConsultationTrace::getCreatedAt).reversed())
                .map(this::parse)
                .filter(item -> requestedPhase == null
                        || requestedPhase.equalsIgnoreCase(item.trace().getTerminalPhase()))
                .filter(item -> status == null || status.equalsIgnoreCase(item.status()))
                .filter(item -> requestedFailure == null
                        || requestedFailure.equalsIgnoreCase(item.failureCode()))
                .filter(item -> !timeoutFilter || isTimeout(item.failureCode()))
                .filter(item -> node == null || containsNode(item.events(), node))
                .filter(item -> sessionId == null || sessionId.equals(item.trace().getSessionId()))
                .filter(item -> startTime == null || !item.trace().getCreatedAt().isBefore(startTime))
                .filter(item -> endTime == null || !item.trace().getCreatedAt().isAfter(endTime))
                .toList();

        long offset = (long) page * size;
        int from = (int) Math.min(offset, filtered.size());
        int to = Math.min(from + size, filtered.size());
        List<Map<String, Object>> result = filtered.subList(from, to).stream()
                .map(this::summary)
                .toList();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("page", page);
        meta.put("size", size);
        meta.put("total", filtered.size());
        meta.put("pages", (filtered.size() + size - 1) / size);
        return ResponseEntity.ok(ApiResponse.ok(result, meta));
    }

    /** Aggregate failure, timeout and per-node timing metrics from persisted events. */
    @GetMapping({"/stats", "/trace/stats"})
    public ResponseEntity<ApiResponse<Map<String, Object>>> stats(
            @RequestParam(required = false) Instant startTime,
            @RequestParam(required = false) Instant endTime) {
        List<ParsedTrace> selected = traceRepository.findAll().stream()
                .map(this::parse)
                .filter(item -> startTime == null || !item.trace().getCreatedAt().isBefore(startTime))
                .filter(item -> endTime == null || !item.trace().getCreatedAt().isAfter(endTime))
                .toList();

        long failed = selected.stream().filter(item -> "failed".equals(item.status())).count();
        long cancelled = selected.stream()
                .filter(item -> "cancelled".equals(item.status()))
                .count();
        List<ParsedTrace> completed = selected.stream()
                .filter(item -> "completed".equals(item.status()))
                .toList();
        long timeoutCount = selected.stream()
                .filter(item -> isTimeout(item.failureCode()))
                .count();
        Map<String, Integer> errorCodes = new LinkedHashMap<>();
        selected.stream()
                .filter(item -> "failed".equals(item.status()))
                .map(ParsedTrace::failureCode)
                .filter(code -> code != null && !code.isBlank())
                .forEach(code -> errorCodes.merge(code, 1, Integer::sum));

        Map<String, NodeMetric> nodeMetrics = new LinkedHashMap<>();
        selected.forEach(item -> accumulateNodeStats(item.events(), nodeMetrics));
        Map<String, Object> nodes = new LinkedHashMap<>();
        nodeMetrics.forEach((node, metric) -> nodes.put(node, metric.asMap()));

        Map<String, Object> data = new LinkedHashMap<>();
        data.put("totalTraces", selected.size());
        data.put("completedTraces", completed.size());
        data.put("failedTraces", failed);
        data.put("cancelledTraces", cancelled);
        data.put("timeoutTraces", timeoutCount);
        data.put("averageDurationMs", completed.isEmpty()
                ? null
                : completed.stream()
                        .mapToLong(item -> totalDuration(item.trace(), item.events()))
                        .average()
                        .orElse(0.0));
        data.put("errorCodes", errorCodes);
        data.put("errors", errorCodes);
        data.put("nodes", nodes);
        data.put("nodeDurations", nodes);
        return ResponseEntity.ok(ApiResponse.ok(data));
    }

    private ParsedTrace parse(ConsultationTrace trace) {
        JsonNode events = readJson(trace.getEventsJson());
        String code = failureCodeOf(trace, events);
        return new ParsedTrace(trace, events, code, statusOf(trace, events));
    }

    private Map<String, Object> summary(ParsedTrace item) {
        ConsultationTrace trace = item.trace();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("traceId", trace.getTraceId());
        result.put("sessionId", trace.getSessionId());
        result.put("terminalPhase", trace.getTerminalPhase());
        result.put("status", item.status());
        result.put("failureCode", item.failureCode());
        result.put("totalDurationMs", totalDuration(trace, item.events()));
        result.put("followupPending", trace.isFollowupPending());
        result.put("createdAt", trace.getCreatedAt().toString());
        return result;
    }

    private String statusOf(ConsultationTrace trace, JsonNode events) {
        if ("cancelled".equalsIgnoreCase(trace.getTerminalPhase())) return "cancelled";
        return "failed".equalsIgnoreCase(trace.getTerminalPhase())
                || failureCodeOf(trace, events) != null ? "failed" : "completed";
    }

    private String failureCodeOf(ConsultationTrace trace, JsonNode events) {
        if (trace.getFailureCode() != null && !trace.getFailureCode().isBlank()) {
            return trace.getFailureCode();
        }
        if (events != null && events.isArray()) {
            for (JsonNode event : events) {
                if ("error".equals(event.path("type").asText())
                        || "error".equals(event.path("status").asText())) {
                    String code = event.path("data").path("code").asText(null);
                    return code == null || code.isBlank() ? "UPSTREAM_STREAM_ERROR" : code;
                }
            }
        }
        return null;
    }

    private Map<String, Object> nodeStats(JsonNode events) {
        Map<String, NodeMetric> metrics = new LinkedHashMap<>();
        accumulateNodeStats(events, metrics);
        Map<String, Object> result = new LinkedHashMap<>();
        metrics.forEach((node, metric) -> result.put(node, metric.asMap()));
        return result;
    }

    private long totalDuration(ConsultationTrace trace, JsonNode events) {
        if (trace.getTotalDurationMs() > 0) {
            return trace.getTotalDurationMs();
        }
        Map<String, NodeMetric> metrics = new LinkedHashMap<>();
        accumulateNodeStats(events, metrics);
        return metrics.values().stream().mapToLong(metric -> metric.totalMs).sum();
    }

    private void accumulateNodeStats(JsonNode events, Map<String, NodeMetric> metrics) {
        if (events == null || !events.isArray()) return;
        for (JsonNode event : events) {
            if (!"node".equals(event.path("type").asText())) continue;
            String node = event.path("node").asText(null);
            String status = event.path("status").asText("");
            if (node == null || !("completed".equals(status) || "error".equals(status))) continue;
            NodeMetric metric = metrics.computeIfAbsent(node, ignored -> new NodeMetric());
            metric.count++;
            metric.totalMs += Math.max(0L, event.path("elapsed_ms").asLong(0));
            metric.maxMs = Math.max(metric.maxMs, Math.max(0L, event.path("elapsed_ms").asLong(0)));
            if ("error".equals(status)) metric.errorCount++;
        }
    }

    private JsonNode readJson(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("stored trace JSON is invalid", ex);
        }
    }

    private static String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second != null && !second.isBlank() ? second : null;
    }

    private static String firstNonBlank(String first, String second, String third) {
        return firstNonBlank(first, firstNonBlank(second, third));
    }

    private static boolean containsNode(JsonNode events, String requestedNode) {
        if (events == null || !events.isArray() || requestedNode == null) return false;
        for (JsonNode event : events) {
            if (requestedNode.equals(event.path("node").asText())) return true;
        }
        return false;
    }

    private static boolean isTimeout(String code) {
        return code != null && code.toLowerCase(Locale.ROOT).contains("timeout");
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 100");
        }
    }

    private record ParsedTrace(
            ConsultationTrace trace,
            JsonNode events,
            String failureCode,
            String status) {
    }

    private static final class NodeMetric {
        private long count;
        private long errorCount;
        private long totalMs;
        private long maxMs;

        private Map<String, Object> asMap() {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("count", count);
            result.put("errorCount", errorCount);
            result.put("totalDurationMs", totalMs);
            result.put("averageDurationMs", count == 0 ? 0.0 : (double) totalMs / count);
            result.put("maxDurationMs", maxMs);
            return result;
        }
    }
}
