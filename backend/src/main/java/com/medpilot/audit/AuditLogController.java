package com.medpilot.audit;

import com.medpilot.common.ApiResponse;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api/audit")
public class AuditLogController {
    private final AuditLogRepository repository;
    public AuditLogController(AuditLogRepository repository) { this.repository = repository; }

    @GetMapping("/logs")
    public ApiResponse<List<Map<String, Object>>> logs(
            @RequestParam(required = false) String actor,
            @RequestParam(required = false) Integer status,
            @RequestParam(required = false) String statusGroup,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        validatePage(page, size);
        int[] statusRange = resolveStatusRange(status, statusGroup);
        Integer statusMin = statusRange == null ? null : statusRange[0];
        Integer statusMax = statusRange == null ? null : statusRange[1];
        var result = repository.search(actor, statusMin, statusMax, from, to,
                PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "createdAt")));
        List<Map<String, Object>> rows = result.getContent().stream().map(this::payload).toList();
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("page", result.getNumber()); meta.put("size", result.getSize());
        meta.put("total", result.getTotalElements()); meta.put("pages", result.getTotalPages());
        return ApiResponse.ok(rows, meta);
    }

    /** Deliberately excludes actor username, IP, request values and medical payloads. */
    @GetMapping(value = "/export", produces = "text/csv;charset=UTF-8")
    public ResponseEntity<String> export(
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to) {
        var result = repository.search(null, null, null, from, to,
                PageRequest.of(0, 10_000, Sort.by(Sort.Direction.ASC, "createdAt")));
        StringBuilder csv = new StringBuilder("event_id,actor_role,action,status,success,created_at,duration_ms\n");
        result.getContent().forEach(log -> csv.append(row(log.getEventId(), log.getActorRole(), log.getAction(),
                Integer.toString(log.getStatus()), Boolean.toString(log.isSuccess()),
                log.getCreatedAt().toString(), Long.toString(log.getDurationMs()))));
        return ResponseEntity.ok().contentType(MediaType.parseMediaType("text/csv;charset=UTF-8"))
                .header("Content-Disposition", "attachment; filename=medpilot-audit.csv")
                .body(csv.toString());
    }

    private Map<String, Object> payload(AuditLog log) {
        Map<String, Object> data = new LinkedHashMap<>();
        data.put("eventId", log.getEventId()); data.put("actor", log.getActorUsername());
        data.put("role", log.getActorRole()); data.put("action", log.getAction());
        data.put("status", log.getStatus()); data.put("success", log.isSuccess());
        data.put("requestId", log.getRequestId()); data.put("durationMs", log.getDurationMs());
        data.put("createdAt", log.getCreatedAt().toString());
        return data;
    }

    private String row(String... values) {
        return Arrays.stream(values).map(this::csvCell).collect(Collectors.joining(",")) + "\n";
    }
    private String csvCell(String value) {
        String safe = value == null ? "" : value.replace("\r", " ").replace("\n", " ");
        return "\"" + safe.replace("\"", "\"\"") + "\"";
    }
    private void validatePage(int page, int size) {
        if (page < 0 || size < 1 || size > 200) {
            throw new IllegalArgumentException("page must be non-negative and size must be between 1 and 200");
        }
    }

    private int[] resolveStatusRange(Integer status, String statusGroup) {
        String group = statusGroup == null ? "" : statusGroup.strip().toLowerCase(Locale.ROOT);
        if (status != null && !group.isEmpty()) {
            throw new IllegalArgumentException("status and statusGroup cannot be combined");
        }
        if (status != null) {
            if (status < 100 || status > 599) {
                throw new IllegalArgumentException("status must be between 100 and 599");
            }
            return new int[] {status, status};
        }
        return switch (group) {
            case "" -> null;
            case "success" -> new int[] {200, 399};
            case "client_error" -> new int[] {400, 499};
            case "server_error" -> new int[] {500, 599};
            default -> throw new IllegalArgumentException(
                    "statusGroup must be success, client_error or server_error");
        };
    }
}
