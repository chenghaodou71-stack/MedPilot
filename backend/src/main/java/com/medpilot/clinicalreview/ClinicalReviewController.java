package com.medpilot.clinicalreview;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.medpilot.common.ApiResponse;
import com.medpilot.user.User;
import com.medpilot.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** HTTP boundary for the doctor/reviewer queue and explicit decisions. */
@RestController
@RequestMapping("/api/clinical-reviews")
public class ClinicalReviewController {

    private final ClinicalReviewService reviewService;
    private final UserRepository users;

    public ClinicalReviewController(ClinicalReviewService reviewService, UserRepository users) {
        this.reviewService = reviewService;
        this.users = users;
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            Authentication authentication,
            @RequestParam(required = false) ClinicalReviewStatus status) {
        User actor = currentUser(authentication);
        return ApiResponse.ok(reviewService.list(actor, status).stream().map(this::view).toList());
    }

    @GetMapping("/{id}")
    public ApiResponse<Map<String, Object>> detail(
            Authentication authentication,
            @PathVariable String id) {
        return ApiResponse.ok(view(reviewService.get(currentUser(authentication), id)));
    }

    @PostMapping("/{id}/claim")
    public ApiResponse<Map<String, Object>> claim(
            Authentication authentication,
            @PathVariable String id) {
        return ApiResponse.ok(view(reviewService.claim(currentUser(authentication), id)));
    }

    @PostMapping({"/{id}/decision", "/{id}/decide"})
    public ApiResponse<Map<String, Object>> decide(
            Authentication authentication,
            @PathVariable String id,
            @Valid @RequestBody DecisionRequest request) {
        ClinicalReview review = reviewService.decide(
                currentUser(authentication),
                id,
                request.decision(),
                request.finalDepartment(),
                request.finalRiskLevel(),
                request.finalUrgency(),
                request.reason());
        return ApiResponse.ok(view(review));
    }

    private User currentUser(Authentication authentication) {
        if (authentication == null || authentication.getName() == null) {
            throw new SecurityException("authenticated clinician is required");
        }
        return users.findByUsername(authentication.getName())
                .filter(user -> user.isLoginEligibleAt(Instant.now()))
                .orElseThrow(() -> new SecurityException("authenticated user not found"));
    }

    private Map<String, Object> view(ClinicalReview review) {
        Map<String, Object> data = new LinkedHashMap<>();
        put(data, "id", review.getId());
        put(data, "reviewId", review.getReviewId());
        put(data, "consultationRecordId", review.getConsultationRecordId());
        put(data, "recordId", review.getConsultationRecordId());
        put(data, "patientMpiId", review.getPatientMpiId());
        put(data, "organizationCode", review.getOrganizationCode());
        put(data, "campusCode", review.getCampusCode());
        put(data, "encounterDepartmentCode", review.getEncounterDepartmentCode());
        put(data, "aiTraceId", review.getAiTraceId());
        put(data, "status", review.getStatus().name());
        put(data, "decision", review.getDecision() == null ? null : review.getDecision().name());
        put(data, "originalDepartment", review.getOriginalDepartment());
        put(data, "originalRiskLevel", review.getOriginalRiskLevel());
        put(data, "originalUrgency", review.getOriginalUrgency());
        put(data, "finalDepartment", review.getFinalDepartment());
        put(data, "finalRiskLevel", review.getFinalRiskLevel());
        put(data, "finalUrgency", review.getFinalUrgency());
        put(data, "claimedByUserId", review.getClaimedByUserId());
        put(data, "reviewerUserId", review.getReviewerUserId());
        put(data, "reviewerEmployeeNumber", review.getReviewerEmployeeNumber());
        put(data, "claimedAt", instant(review.getClaimedAt()));
        put(data, "decisionReason", review.getDecisionReason());
        put(data, "reason", review.getDecisionReason());
        put(data, "decidedAt", instant(review.getDecidedAt()));
        put(data, "emergencyEscalatedAt", instant(review.getEmergencyEscalatedAt()));
        put(data, "createdAt", instant(review.getCreatedAt()));
        put(data, "updatedAt", instant(review.getUpdatedAt()));
        return data;
    }

    private static void put(Map<String, Object> target, String key, Object value) {
        target.put(key, value);
    }

    private static String instant(Instant value) {
        return value == null ? null : value.toString();
    }

    public record DecisionRequest(
            @NotNull ClinicalReviewDecision decision,
            @JsonAlias({"final_department", "department"}) @Size(max = 128)
            String finalDepartment,
            @JsonAlias({"final_risk_level", "riskLevel"}) @Size(max = 32)
            String finalRiskLevel,
            @JsonAlias({"final_urgency", "urgency"}) @Size(max = 512)
            String finalUrgency,
            @NotBlank @Size(max = 2_000)
            @JsonAlias({"decision_reason", "comment"}) String reason) {
    }
}
