package com.medpilot.consult;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medpilot.common.ApiResponse;
import com.medpilot.user.User;
import com.medpilot.user.UserRepository;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Arrays;

@RestController
@RequestMapping("/api/records")
public class ConsultationRecordController {

    private final ConsultationRecordRepository records;
    private final UserRepository users;
    private final ObjectMapper mapper;

    public ConsultationRecordController(
            ConsultationRecordRepository records,
            UserRepository users,
            ObjectMapper mapper) {
        this.records = records;
        this.users = users;
        this.mapper = mapper;
    }

    private Long getUserId(Authentication auth) {
        User user = users.findByUsername(auth.getName())
                .orElseThrow(() -> new IllegalStateException("User not found"));
        return user.getId();
    }

    private boolean isAdmin(Authentication auth) {
        return auth.getAuthorities().contains(new SimpleGrantedAuthority("ROLE_ADMIN"));
    }

    /** 列表检索：普通用户仅见本人记录，管理员见全量。支持按症状/科室/时间筛选。 */
    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            Authentication auth,
            @RequestParam(required = false) String symptoms,
            @RequestParam(required = false) String department,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant startTime
    ) {
        List<ConsultationRecord> list;
        if (isAdmin(auth)) {
            list = records.searchAll(department, startTime);
        } else {
            Long userId = getUserId(auth);
            list = records.searchByUser(userId, department, startTime);
        }
        if (symptoms != null && !symptoms.isBlank()) {
            list = list.stream()
                    .filter(record -> record.getSymptoms() != null
                            && record.getSymptoms().contains(symptoms))
                    .toList();
        }
        var result = list.stream().map(r -> {
            Map<String, Object> m = Map.of(
                    "id", r.getId(),
                    "sessionId", r.getSessionId(),
                    "symptoms", r.getSymptoms() != null ? r.getSymptoms() : "",
                    "department", r.getDepartment() != null ? r.getDepartment() : "",
                    "riskLevel", r.getRiskLevel() != null ? r.getRiskLevel() : "",
                    "supportScore", r.getSupportScore() != null ? r.getSupportScore() : 0.0,
                    "urgency", r.getUrgency() != null ? r.getUrgency() : "",
                    "createdAt", r.getCreatedAt().toString()
            );
            return m;
        }).toList();
        return ApiResponse.ok(result);
    }

    /** 详情查看：返回完整记录。普通用户只能查本人，管理员可查全量。 */
    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(@PathVariable Long id, Authentication auth) {
        ConsultationRecord rec = records.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Record not found"));

        if (!isAdmin(auth)) {
            Long userId = getUserId(auth);
            if (!rec.getUserId().equals(userId)) {
                throw new SecurityException("Access denied");
            }
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
        return ApiResponse.ok(data);
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
}
