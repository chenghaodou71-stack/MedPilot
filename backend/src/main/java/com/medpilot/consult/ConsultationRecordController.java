package com.medpilot.consult;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medpilot.common.ApiResponse;
import com.medpilot.hospital.HospitalRecordAccessService;
import com.medpilot.clinicalreview.ClinicalReview;
import com.medpilot.clinicalreview.ClinicalReviewRepository;
import com.medpilot.user.User;
import com.medpilot.user.UserRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;

@RestController
@RequestMapping("/api/records")
public class ConsultationRecordController {

    private final ConsultationRecordRepository records;
    private final UserRepository users;
    private final HospitalRecordAccessService recordAccess;
    private final ObjectMapper mapper;
    private final ClinicalReviewRepository clinicalReviews;

    @org.springframework.beans.factory.annotation.Autowired
    public ConsultationRecordController(
            ConsultationRecordRepository records,
            UserRepository users,
            HospitalRecordAccessService recordAccess,
            ObjectMapper mapper,
            ClinicalReviewRepository clinicalReviews) {
        this.records = records;
        this.users = users;
        this.recordAccess = recordAccess;
        this.mapper = mapper;
        this.clinicalReviews = clinicalReviews;
    }

    /** Compatibility constructor for isolated controller tests. */
    public ConsultationRecordController(
            ConsultationRecordRepository records,
            UserRepository users,
            HospitalRecordAccessService recordAccess,
            ObjectMapper mapper) {
        this(records, users, recordAccess, mapper, null);
    }

    /** Server-side combined filtering and pagination under owner/care-team access policy. */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            Authentication auth,
            @RequestParam(required = false) Long id,
            @RequestParam(required = false) String sessionId,
            @RequestParam(required = false) String symptoms,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String query,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant endTime,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size
    ) {
        validatePage(page, size);
        if (startTime != null && endTime != null && startTime.isAfter(endTime)) {
            throw new IllegalArgumentException("startTime must not be after endTime");
        }
        String normalizedSession = blankToNull(sessionId);
        String normalizedDepartment = blankToNull(department);
        User actor = currentUser(auth);
        List<ConsultationRecord> list = records.queryReadableForActor(
                actor.getId(), actor.getOrganizationCode(), Instant.now(),
                id, normalizedSession, normalizedDepartment, startTime, endTime);
        String symptomQuery = blankToNull(symptoms);
        if (symptomQuery != null) {
            String folded = symptomQuery.toLowerCase(Locale.ROOT);
            list = list.stream()
                    .filter(record -> record.getSymptoms() != null
                            && record.getSymptoms().toLowerCase(Locale.ROOT).contains(folded))
                    .toList();
        }
        String broadQuery = firstNonBlank(keyword, query);
        if (broadQuery != null) {
            String folded = broadQuery.toLowerCase(Locale.ROOT);
            list = list.stream()
                    .filter(record -> contains(record.getId().toString(), folded)
                            || contains(record.getSessionId(), folded)
                            || contains(record.getSymptoms(), folded)
                            || contains(record.getDepartment(), folded))
                    .toList();
        }
        int total = list.size();
        long offset = (long) page * size;
        int from = (int) Math.min(offset, total);
        int to = Math.min(from + size, total);
        var result = list.subList(from, to).stream().map(this::summary).toList();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("page", page);
        meta.put("size", size);
        meta.put("total", total);
        meta.put("pages", (total + size - 1) / size);
        return ApiResponse.ok(result, meta);
    }

    /** 详情查看：返回完整记录。普通用户只能查本人，管理员可查全量。 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id, Authentication auth) {
        ConsultationRecord rec = records.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Record not found"));

        User actor = currentUser(auth);
        if (!recordAccess.canRead(actor, rec)) {
            throw new SecurityException("Access denied");
        }

        Map<String, Object> data = new java.util.HashMap<>();
        data.put("id", rec.getId());
        data.put("sessionId", rec.getSessionId());
        data.put("traceId", rec.getTraceId() != null ? rec.getTraceId() : "");
        data.put("symptoms", rec.getSymptoms() != null ? rec.getSymptoms() : "");
        data.put("department", rec.getDepartment() != null ? rec.getDepartment() : "");
        data.put("riskLevel", rec.getRiskLevel() != null ? rec.getRiskLevel() : "");
        data.put("confidence", rec.getConfidence() != null ? rec.getConfidence() : 0.0);
        data.put("supportScore", rec.getSupportScore() != null ? rec.getSupportScore() : 0.0);
        data.put("abstained", rec.isAbstained());
        data.put("urgency", rec.getUrgency() != null ? rec.getUrgency() : "");
        data.put("matchedRule", rec.getMatchedRule() != null ? rec.getMatchedRule() : "");
        data.put("explanation", rec.getExplanation() != null ? rec.getExplanation() : "");
        data.put("triageFactors", parseJsonArray(rec.getTriageFactors()));
        data.put("answer", rec.getAnswer() != null ? rec.getAnswer() : "");
        data.put("citations", parseCitations(rec.getCitations()));
        data.put("conversationHistory", rec.getConversationHistory() != null ? rec.getConversationHistory() : "");
        data.put("createdAt", rec.getCreatedAt().toString());
        addReview(data, rec);
        return ApiResponse.ok(data);
    }

    private Map<String, Object> summary(ConsultationRecord record) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("id", record.getId());
        data.put("sessionId", record.getSessionId());
        data.put("symptoms", record.getSymptoms() != null ? record.getSymptoms() : "");
        data.put("department", record.getDepartment() != null ? record.getDepartment() : "");
        data.put("riskLevel", record.getRiskLevel() != null ? record.getRiskLevel() : "");
        data.put("supportScore", record.getSupportScore() != null ? record.getSupportScore() : 0.0);
        data.put("urgency", record.getUrgency() != null ? record.getUrgency() : "");
        data.put("createdAt", record.getCreatedAt().toString());
        addReview(data, record);
        return data;
    }

    private void addReview(Map<String, Object> target, ConsultationRecord record) {
        if (clinicalReviews == null || record.getId() == null) {
            target.put("reviewStatus", "UNKNOWN");
            target.put("reviewRequired", false);
            return;
        }
        ClinicalReview review = clinicalReviews.findByConsultationRecordId(record.getId()).orElse(null);
        target.put("reviewStatus", review == null ? "PENDING_REVIEW" : review.getStatus().name());
        target.put("reviewRequired", review == null || !review.isTerminal());
        target.put("reviewId", review == null ? null : review.getReviewId());
        target.put("reviewDecision", review == null || review.getDecision() == null
                ? null : review.getDecision().name());
        target.put("reviewedAt", review == null || review.getDecidedAt() == null
                ? null : review.getDecidedAt().toString());
        target.put("finalDepartment", review == null ? null : review.getFinalDepartment());
        target.put("finalRiskLevel", review == null ? null : review.getFinalRiskLevel());
        target.put("finalUrgency", review == null ? null : review.getFinalUrgency());
    }

    private User currentUser(Authentication auth) {
        return users.findByUsername(auth.getName())
                .filter(user -> user.isLoginEligibleAt(Instant.now()))
                .orElseThrow(() -> new SecurityException("authenticated user not found"));
    }

    private Object parseCitations(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            JsonNode parsed = mapper.readTree(raw);
            if (parsed.isArray()) return parsed;
        } catch (JsonProcessingException ignored) {
            // Legacy records used a comma-separated source list.
        }
        return Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .map(value -> Map.of("source", value))
                .toList();
    }

    private Object parseJsonArray(String raw) {
        if (raw == null || raw.isBlank()) return List.of();
        try {
            JsonNode parsed = mapper.readTree(raw);
            return parsed.isArray() ? parsed : List.of();
        } catch (JsonProcessingException ignored) {
            return List.of();
        }
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static String firstNonBlank(String first, String second) {
        String normalized = blankToNull(first);
        return normalized != null ? normalized : blankToNull(second);
    }

    private static boolean contains(String value, String foldedQuery) {
        return value != null && value.toLowerCase(Locale.ROOT).contains(foldedQuery);
    }

    private static void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 100) {
            throw new IllegalArgumentException(
                    "page must be non-negative and size must be between 1 and 100");
        }
    }
}
