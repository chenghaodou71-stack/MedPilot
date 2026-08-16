package com.medpilot.health;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medpilot.common.ApiResponse;
import com.medpilot.consult.ConsultationRecord;
import com.medpilot.consult.ConsultationRecordRepository;
import com.medpilot.user.User;
import com.medpilot.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/profile")
public class HealthProfileController {

    private final HealthProfileRepository profiles;
    private final FollowUpTaskRepository followUps;
    private final ConsultationRecordRepository records;
    private final UserRepository users;
    private final ObjectMapper mapper;

    public HealthProfileController(
            HealthProfileRepository profiles,
            FollowUpTaskRepository followUps,
            ConsultationRecordRepository records,
            UserRepository users,
            ObjectMapper mapper) {
        this.profiles = profiles;
        this.followUps = followUps;
        this.records = records;
        this.users = users;
        this.mapper = mapper;
    }

    @GetMapping
    public ApiResponse<Map<String, Object>> getProfile(Authentication authentication) {
        Long userId = userId(authentication);
        return ApiResponse.ok(profiles.findByUserId(userId)
                .map(this::profilePayload)
                .orElseGet(() -> emptyProfilePayload(false)));
    }

    @PutMapping
    public ApiResponse<Map<String, Object>> updateProfile(
            @Valid @RequestBody ProfileRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        boolean consent = request.consentGranted();
        String json = writeProfile(request, consent);
        HealthProfile profile = profiles.findByUserId(userId).orElse(null);
        if (profile == null) {
            profile = new HealthProfile(userId, json, consent);
        } else {
            profile.update(json, consent);
        }
        return ApiResponse.ok(profilePayload(profiles.save(profile)));
    }

    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteProfile(Authentication authentication) {
        profiles.findByUserId(userId(authentication)).ifPresent(profiles::delete);
    }

    @GetMapping("/timeline")
    public ApiResponse<List<Map<String, Object>>> timeline(Authentication authentication) {
        Long userId = userId(authentication);
        return ApiResponse.ok(records.searchByUser(userId, null, null).stream()
                .map(this::timelinePayload)
                .toList());
    }

    @GetMapping("/follow-ups")
    public ApiResponse<List<Map<String, Object>>> listFollowUps(Authentication authentication) {
        return ApiResponse.ok(followUps.findByUserIdOrderByDueAtAsc(userId(authentication)).stream()
                .map(this::followUpPayload)
                .toList());
    }

    /**
     * Returns actionable reminders whose due time has passed. The query is
     * scoped by owner and OPEN status so completed/cancelled tasks can never
     * leak back into the notification center.
     */
    @GetMapping("/follow-ups/due")
    public ApiResponse<List<Map<String, Object>>> listDueFollowUps(
            Authentication authentication) {
        Instant now = Instant.now();
        return ApiResponse.ok(followUps
                .findByUserIdAndStatusAndDueAtLessThanEqualOrderByDueAtAsc(
                        userId(authentication), FollowUpTask.Status.OPEN, now)
                .stream()
                .map(task -> followUpPayload(task, now))
                .toList());
    }

    @PostMapping("/follow-ups")
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Map<String, Object>> createFollowUp(
            @Valid @RequestBody FollowUpRequest request,
            Authentication authentication) {
        Long userId = userId(authentication);
        if (request.recordId() != null) {
            ConsultationRecord record = records.findById(request.recordId())
                    .orElseThrow(() -> new IllegalArgumentException("Record not found"));
            if (!record.getUserId().equals(userId)) {
                throw new SecurityException("Access denied");
            }
        }
        FollowUpTask task = new FollowUpTask(
                userId,
                request.recordId(),
                request.title().trim(),
                normalize(request.notes()),
                request.dueAt());
        return ApiResponse.ok(followUpPayload(followUps.save(task)));
    }

    @PatchMapping("/follow-ups/{id}")
    public ApiResponse<Map<String, Object>> updateFollowUp(
            @PathVariable Long id,
            @Valid @RequestBody FollowUpStatusRequest request,
            Authentication authentication) {
        FollowUpTask task = followUps.findByIdAndUserId(id, userId(authentication))
                .orElseThrow(() -> new SecurityException("Access denied"));
        try {
            task.setStatus(FollowUpTask.Status.valueOf(request.status().trim().toUpperCase()));
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("Unsupported follow-up status");
        }
        return ApiResponse.ok(followUpPayload(followUps.save(task)));
    }

    private Long userId(Authentication authentication) {
        User user = users.findByUsername(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("User not found"));
        return user.getId();
    }

    private String writeProfile(ProfileRequest request, boolean consent) {
        Map<String, String> values = new LinkedHashMap<>();
        values.put("allergies", consent ? normalize(request.allergies()) : "");
        values.put("conditions", consent ? normalize(request.conditions()) : "");
        values.put("medications", consent ? normalize(request.medications()) : "");
        values.put("notes", consent ? normalize(request.notes()) : "");
        try {
            return mapper.writeValueAsString(values);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("failed to serialize health profile", ex);
        }
    }

    private Map<String, Object> profilePayload(HealthProfile profile) {
        Map<String, Object> payload = emptyProfilePayload(profile.isConsentGranted());
        payload.put("updatedAt", profile.getUpdatedAt().toString());
        try {
            Map<String, String> values = mapper.readValue(
                    profile.getProfileJson(), new TypeReference<>() {});
            values.forEach((key, value) -> payload.put(key, value != null ? value : ""));
        } catch (JsonProcessingException ignored) {
            payload.put("profileError", "档案内容暂时无法解析");
        }
        return payload;
    }

    private Map<String, Object> emptyProfilePayload(boolean consent) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("allergies", "");
        payload.put("conditions", "");
        payload.put("medications", "");
        payload.put("notes", "");
        payload.put("consentGranted", consent);
        return payload;
    }

    private Map<String, Object> timelinePayload(ConsultationRecord record) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", record.getId());
        payload.put("createdAt", record.getCreatedAt().toString());
        payload.put("symptoms", normalize(record.getSymptoms()));
        payload.put("department", normalize(record.getDepartment()));
        payload.put("riskLevel", normalize(record.getRiskLevel()));
        payload.put("urgency", normalize(record.getUrgency()));
        payload.put("supportScore", record.getSupportScore() != null ? record.getSupportScore() : 0.0);
        return payload;
    }

    private Map<String, Object> followUpPayload(FollowUpTask task) {
        return followUpPayload(task, Instant.now());
    }

    private Map<String, Object> followUpPayload(FollowUpTask task, Instant now) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("id", task.getId());
        payload.put("recordId", task.getRecordId());
        payload.put("title", task.getTitle());
        payload.put("notes", normalize(task.getNotes()));
        payload.put("dueAt", task.getDueAt().toString());
        payload.put("status", task.getStatus().name());
        payload.put("due", !task.getDueAt().isAfter(now));
        payload.put("createdAt", task.getCreatedAt().toString());
        return payload;
    }

    private String normalize(String value) {
        return value == null ? "" : value.trim();
    }

    public record ProfileRequest(
            @Size(max = 4000) String allergies,
            @Size(max = 4000) String conditions,
            @Size(max = 4000) String medications,
            @Size(max = 4000) String notes,
            boolean consentGranted) {
    }

    public record FollowUpRequest(
            @NotBlank @Size(max = 256) String title,
            @NotNull Instant dueAt,
            Long recordId,
            @Size(max = 4000) String notes) {
    }

    public record FollowUpStatusRequest(@NotBlank String status) {
    }
}
