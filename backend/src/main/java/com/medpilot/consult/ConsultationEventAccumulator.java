package com.medpilot.consult;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Strict validator and immutable snapshot builder for one AI NDJSON trace. */
public final class ConsultationEventAccumulator {

    private static final Set<String> INTENTS = Set.of("medical_consult", "emergency");
    private static final Set<String> PHASES = Set.of(
            "screening", "collecting", "summarizing", "retrieving", "triaging",
            "composing", "awaiting_followup", "completed", "escalated", "failed");
    private static final Set<String> HISTORY_MODES = Set.of("full", "summary");

    private final ObjectMapper mapper;
    private final String expectedSessionId;
    private final List<JsonNode> events = new ArrayList<>();
    private final Map<String, String> nodeStates = new HashMap<>();
    private final ArrayNode evidenceCitations;

    private int expectedSequence = 1;
    private String traceId;
    private boolean sawDone;
    private boolean sawError;
    private boolean sawTerminalError;
    private boolean followupPending;
    private String terminalPhase;
    private String symptoms;
    private String department;
    private String riskLevel;
    private Double confidence;
    private Double supportScore;
    private boolean abstained;
    private String urgency;
    private String matchedRule;
    private String explanation;
    private String triageFactorsJson = "[]";
    private String answer;
    private ArrayNode answerCitations;
    private JsonNode composedAnswer;
    private final StringBuilder answerDeltas = new StringBuilder();
    private boolean sawAnswerDelta;
    private String failureCode;
    private long totalDurationMs;

    public ConsultationEventAccumulator(ObjectMapper mapper, String expectedSessionId) {
        this.mapper = mapper;
        this.expectedSessionId = SessionOwnershipService.canonicalUuid(expectedSessionId);
        this.evidenceCitations = mapper.createArrayNode();
    }

    public synchronized void accept(String line) {
        if (sawDone) {
            throw new IllegalArgumentException("event received after done");
        }
        if (sawTerminalError) {
            throw new IllegalArgumentException("event received after terminal error");
        }
        JsonNode event = parse(line);
        validateEnvelope(event);

        String type = requiredText(event, "type");
        String status = textOrNull(event.get("status"));
        if ("node".equals(type)
                && ("completed".equals(status) || "error".equals(status))) {
            totalDurationMs += event.path("elapsed_ms").asLong(0);
        }
        switch (type) {
            case "node" -> acceptNode(event);
            case "answer_delta" -> acceptAnswerDelta(event);
            case "done" -> acceptDone(event);
            case "error" -> {
                sawError = true;
                sawTerminalError = true;
                failureCode = textOrNull(event.path("data").get("code"));
                if (failureCode == null) {
                    failureCode = "UPSTREAM_STREAM_ERROR";
                }
            }
            default -> throw new IllegalArgumentException("unsupported event type: " + type);
        }

        events.add(event.deepCopy());
        expectedSequence++;
    }

    public synchronized Snapshot finish() {
        if (!sawDone) {
            throw new IllegalStateException("trace has no valid done event");
        }
        if (sawError) {
            throw new IllegalStateException("trace contains an error event");
        }
        if (nodeStates.containsValue("started")) {
            throw new IllegalStateException("trace has unfinished node");
        }

        ArrayNode citations = answerCitations != null ? answerCitations : evidenceCitations;
        boolean createRecord = answer != null && !answer.isBlank() && !followupPending;
        return new Snapshot(
                traceId,
                expectedSessionId,
                symptoms,
                department,
                riskLevel,
                confidence,
                urgency,
                matchedRule,
                answer,
                write(citations),
                write(events),
                followupPending,
                terminalPhase,
                createRecord,
                supportScore,
                explanation,
                triageFactorsJson,
                abstained,
                null,
                totalDurationMs
        );
    }

    /** Build an auditable failed snapshot even when the stream has no done event. */
    public synchronized Snapshot failureSnapshot(String code) {
        String normalizedCode = code == null || code.isBlank() ? "UPSTREAM_STREAM_ERROR" : code;
        String eventJson = writeFailureEvents(normalizedCode);
        return new Snapshot(
                ensureTraceId(),
                expectedSessionId,
                symptoms,
                department,
                riskLevel,
                confidence,
                urgency,
                matchedRule,
                null,
                write(evidenceCitations),
                eventJson,
                followupPending,
                "failed",
                false,
                supportScore,
                explanation,
                triageFactorsJson,
                abstained,
                failureCode != null ? failureCode : normalizedCode,
                totalDurationMs
        );
    }

    public synchronized String failureCode() {
        return failureCode;
    }

    public synchronized boolean isDone() {
        return sawDone;
    }

    public synchronized boolean isTerminalError() {
        return sawTerminalError;
    }

    public synchronized String traceId() {
        return traceId;
    }

    /** Build a non-success terminal snapshot when the downstream HTTP client disconnects. */
    public synchronized Snapshot cancelledSnapshot() {
        String code = "CLIENT_CANCELLED";
        return new Snapshot(
                ensureTraceId(),
                expectedSessionId,
                symptoms,
                department,
                riskLevel,
                confidence,
                urgency,
                matchedRule,
                null,
                write(evidenceCitations),
                writeFailureEvents(code),
                followupPending,
                "cancelled",
                false,
                supportScore,
                explanation,
                triageFactorsJson,
                abstained,
                code,
                totalDurationMs
        );
    }

    public synchronized String failureEvent(String code) {
        String eventTraceId = ensureTraceId();
        Map<String, Object> state = new HashMap<>();
        state.put("intent", "medical_consult");
        state.put("phase", "failed");
        state.put("turn_count", 1);
        state.put("history_mode", "full");

        Map<String, Object> event = new HashMap<>();
        event.put("protocol_version", "1.0");
        event.put("trace_id", eventTraceId);
        event.put("session_id", expectedSessionId);
        event.put("sequence", expectedSequence);
        event.put("type", "error");
        event.put("status", "error");
        event.put("elapsed_ms", 0);
        event.put("state", state);
        event.put("data", Map.of(
                "code", code,
                "detail", "consultation failed"));
        return write(event);
    }

    private JsonNode parse(String line) {
        if (line == null || line.isBlank()) {
            throw new IllegalArgumentException("event line must not be blank");
        }
        try {
            JsonNode event = mapper.readTree(line.trim());
            if (event == null || !event.isObject()) {
                throw new IllegalArgumentException("event must be a JSON object");
            }
            return event;
        } catch (JsonProcessingException ex) {
            throw new IllegalArgumentException("invalid NDJSON event", ex);
        }
    }

    private void validateEnvelope(JsonNode event) {
        if (!"1.0".equals(requiredText(event, "protocol_version"))) {
            throw new IllegalArgumentException("unsupported protocol_version");
        }

        String currentTraceId = requiredUuid(event, "trace_id");
        if (traceId == null) {
            traceId = currentTraceId;
        } else if (!traceId.equals(currentTraceId)) {
            throw new IllegalArgumentException("trace_id changed within stream");
        }

        String sessionId = requiredUuid(event, "session_id");
        if (!expectedSessionId.equals(sessionId)) {
            throw new IllegalArgumentException("session_id does not match request");
        }

        JsonNode sequence = event.get("sequence");
        if (sequence == null || !sequence.canConvertToInt() || sequence.asInt() != expectedSequence) {
            throw new IllegalArgumentException("invalid event sequence; expected " + expectedSequence);
        }
        JsonNode elapsed = event.get("elapsed_ms");
        if (elapsed == null || !elapsed.isNumber() || elapsed.asLong() < 0) {
            throw new IllegalArgumentException("elapsed_ms must be non-negative");
        }

        JsonNode state = event.path("state");
        if (!state.isObject()) {
            throw new IllegalArgumentException("state is required");
        }
        requireEnum(state, "intent", INTENTS);
        requireEnum(state, "phase", PHASES);
        requireEnum(state, "history_mode", HISTORY_MODES);
        if (!state.path("turn_count").canConvertToInt() || state.path("turn_count").asInt() < 1) {
            throw new IllegalArgumentException("turn_count must be positive");
        }
        if (!event.path("data").isObject()) {
            throw new IllegalArgumentException("data must be an object");
        }
    }

    private void acceptNode(JsonNode event) {
        String node = requiredText(event, "node");
        String status = requiredText(event, "status");
        String previous = nodeStates.get(node);
        if ("started".equals(status)) {
            if (previous != null) {
                throw new IllegalArgumentException("node started more than once: " + node);
            }
            nodeStates.put(node, "started");
            return;
        }
        if (!"completed".equals(status) && !"error".equals(status)) {
            throw new IllegalArgumentException("invalid node status: " + status);
        }
        if (!"started".equals(previous)) {
            throw new IllegalArgumentException("node ended before started: " + node);
        }
        nodeStates.put(node, status);
        if ("error".equals(status)) {
            sawError = true;
            failureCode = textOrNull(event.path("data").get("code"));
            if (failureCode == null) {
                failureCode = "NODE_ERROR";
            }
            return;
        }
        collectNodeData(node, event.path("data"));
    }

    private void acceptDone(JsonNode event) {
        if (!"completed".equals(requiredText(event, "status"))) {
            throw new IllegalArgumentException("done status must be completed");
        }
        String phase = requiredText(event.path("state"), "phase");
        if (!Set.of("completed", "escalated").contains(phase)) {
            throw new IllegalArgumentException("done phase must be completed or escalated");
        }
        JsonNode doneAnswer = event.path("data").path("answer");
        if (answer != null && !followupPending && !doneAnswer.isObject()) {
            throw new IllegalArgumentException("done answer is required for a final answer");
        }
        if (doneAnswer.isObject()) {
            if (composedAnswer == null || !composedAnswer.equals(doneAnswer)) {
                throw new IllegalArgumentException("done answer does not match compose answer");
            }
        }
        if (answer != null && !followupPending && !sawAnswerDelta) {
            throw new IllegalArgumentException("validated answer requires answer_delta events");
        }
        if (sawAnswerDelta && (answer == null || !answer.equals(answerDeltas.toString()))) {
            throw new IllegalArgumentException("answer_delta content does not match validated answer");
        }
        sawDone = true;
        terminalPhase = phase;
    }

    private void acceptAnswerDelta(JsonNode event) {
        if (!"completed".equals(nodeStates.get("compose")) || answer == null) {
            throw new IllegalArgumentException(
                    "answer_delta requires a validated compose completion");
        }
        if (!"streaming".equals(requiredText(event, "status"))) {
            throw new IllegalArgumentException("answer_delta status must be streaming");
        }
        if (!"composing".equals(requiredText(event.path("state"), "phase"))) {
            throw new IllegalArgumentException("answer_delta phase must be composing");
        }
        JsonNode data = event.path("data");
        if (data.size() != 1 || !data.has("delta")) {
            throw new IllegalArgumentException("answer_delta data must contain only delta");
        }
        JsonNode value = data.get("delta");
        if (value == null || !value.isTextual() || value.asText().isEmpty()) {
            throw new IllegalArgumentException("answer_delta data.delta must be a non-empty string");
        }
        sawAnswerDelta = true;
        answerDeltas.append(value.asText());
        if (!answer.startsWith(answerDeltas.toString())) {
            throw new IllegalArgumentException("answer_delta content diverges from validated answer");
        }
    }

    private void collectNodeData(String node, JsonNode data) {
        switch (node) {
            case "extract" -> {
                JsonNode values = data.path("symptoms").path("symptoms");
                if (values.isArray()) {
                    List<String> parts = new ArrayList<>();
                    values.forEach(value -> {
                        String text = textOrNull(value);
                        if (text != null) parts.add(text);
                    });
                    if (!parts.isEmpty()) symptoms = String.join("、", parts);
                }
            }
            case "retrieve" -> copyCitations(data.path("evidence"), evidenceCitations);
            case "classify" -> {
                JsonNode triage = data.path("triage");
                department = textOrNull(triage.get("department"));
                riskLevel = textOrNull(triage.get("risk_level"));
                confidence = numberOrNull(triage.get("confidence"));
                supportScore = numberOrNull(triage.get("support_score"));
                abstained = triage.path("abstained").asBoolean(false);
                urgency = textOrNull(triage.get("urgency"));
                matchedRule = textOrNull(triage.get("matched_rule"));
                explanation = textOrNull(triage.get("explanation"));
                if (triage.path("factors").isArray()) {
                    triageFactorsJson = write(triage.path("factors"));
                }
            }
            case "compose" -> {
                JsonNode composed = data.path("answer");
                composedAnswer = composed.isObject() ? composed.deepCopy() : null;
                answer = textOrNull(composed.get("text"));
                if (composed.path("citations").isArray()) {
                    answerCitations = mapper.createArrayNode();
                    copyCitations(composed.path("citations"), answerCitations);
                }
            }
            case "ask_followup" -> {
                followupPending = true;
                answer = textOrNull(data.path("followup").get("question"));
            }
            default -> {
                // Unknown nodes remain in the event snapshot for forward compatibility.
            }
        }
    }

    private void copyCitations(JsonNode source, ArrayNode target) {
        if (!source.isArray()) return;
        Set<String> seen = new HashSet<>();
        target.forEach(item -> seen.add(citationKey(item)));
        source.forEach(item -> {
            String key = citationKey(item);
            if (seen.add(key)) target.add(item.deepCopy());
        });
    }

    private String citationKey(JsonNode item) {
        String id = textOrNull(item.get("citation_id"));
        return id != null ? id : item.toString();
    }

    private String write(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize consultation snapshot", ex);
        }
    }

    private String writeFailureEvents(String code) {
        ArrayNode failedEvents = mapper.createArrayNode();
        events.forEach(event -> failedEvents.add(event.deepCopy()));
        if (!sawTerminalError) {
            try {
                failedEvents.add(mapper.readTree(failureEvent(code)));
            } catch (JsonProcessingException ex) {
                throw new IllegalStateException("failed to serialize failure event", ex);
            }
        }
        return write(failedEvents);
    }

    private String ensureTraceId() {
        if (traceId == null) traceId = UUID.randomUUID().toString();
        return traceId;
    }

    private static void requireEnum(JsonNode parent, String field, Set<String> allowed) {
        String value = requiredText(parent, field);
        if (!allowed.contains(value)) {
            throw new IllegalArgumentException("invalid " + field + ": " + value);
        }
    }

    private static String requiredUuid(JsonNode parent, String field) {
        String value = requiredText(parent, field);
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException(field + " must be a UUID");
        }
    }

    private static String requiredText(JsonNode parent, String field) {
        String value = textOrNull(parent.get(field));
        if (value == null) {
            throw new IllegalArgumentException(field + " is required");
        }
        return value;
    }

    private static String textOrNull(JsonNode node) {
        if (node == null || node.isNull() || !node.isValueNode()) return null;
        String value = node.asText().trim();
        return value.isEmpty() ? null : value;
    }

    private static Double numberOrNull(JsonNode node) {
        return node != null && node.isNumber() ? node.asDouble() : null;
    }

    public record Snapshot(
            String traceId,
            String sessionId,
            String symptoms,
            String department,
            String riskLevel,
            Double confidence,
            String urgency,
            String matchedRule,
            String answer,
            String citationsJson,
            String eventsJson,
            boolean followupPending,
            String terminalPhase,
            boolean shouldCreateRecord,
            Double supportScore,
            String explanation,
            String triageFactorsJson,
            boolean abstained,
            String failureCode,
            long totalDurationMs) {

        public Snapshot(
                String traceId,
                String sessionId,
                String symptoms,
                String department,
                String riskLevel,
                Double confidence,
                String urgency,
                String matchedRule,
                String answer,
                String citationsJson,
                String eventsJson,
                boolean followupPending,
                String terminalPhase,
                boolean shouldCreateRecord,
                Double supportScore,
                String explanation,
                String triageFactorsJson,
                boolean abstained) {
            this(traceId, sessionId, symptoms, department, riskLevel, confidence, urgency,
                    matchedRule, answer, citationsJson, eventsJson, followupPending,
                    terminalPhase, shouldCreateRecord, supportScore, explanation,
                    triageFactorsJson, abstained, null, 0L);
        }

        public Snapshot(
                String traceId,
                String sessionId,
                String symptoms,
                String department,
                String riskLevel,
                Double confidence,
                String urgency,
                String matchedRule,
                String answer,
                String citationsJson,
                String eventsJson,
                boolean followupPending,
                String terminalPhase,
                boolean shouldCreateRecord) {
            this(
                    traceId,
                    sessionId,
                    symptoms,
                    department,
                    riskLevel,
                    confidence,
                    urgency,
                    matchedRule,
                    answer,
                    citationsJson,
                    eventsJson,
                    followupPending,
                    terminalPhase,
                    shouldCreateRecord,
                    null,
                    null,
                    "[]",
                    false,
                    null,
                    0L);
        }
    }
}
