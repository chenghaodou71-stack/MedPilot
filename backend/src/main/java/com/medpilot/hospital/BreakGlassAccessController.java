package com.medpilot.hospital;

import com.medpilot.common.ApiResponse;
import com.medpilot.user.User;
import com.medpilot.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/access/break-glass")
public class BreakGlassAccessController {

    private final UserRepository users;
    private final BreakGlassAccessService accessService;

    public BreakGlassAccessController(UserRepository users, BreakGlassAccessService accessService) {
        this.users = users;
        this.accessService = accessService;
    }

    @PostMapping
    public ResponseEntity<ApiResponse<Map<String, Object>>> grant(
            Authentication authentication,
            @Valid @RequestBody BreakGlassRequest request) {
        User clinician = currentUser(authentication);
        BreakGlassAccess access = accessService.grant(
                clinician,
                request.patientMpiId(),
                request.purpose(),
                request.reason(),
                request.durationMinutes());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(view(access)));
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> active(Authentication authentication) {
        return ApiResponse.ok(accessService.activeForClinician(currentUser(authentication)).stream()
                .map(this::view)
                .toList());
    }

    private User currentUser(Authentication authentication) {
        return users.findByUsername(authentication.getName())
                .filter(user -> user.isLoginEligibleAt(java.time.Instant.now()))
                .orElseThrow(() -> new SecurityException("authenticated user not found"));
    }

    private Map<String, Object> view(BreakGlassAccess access) {
        return Map.of(
                "accessId", access.getAccessId(),
                "patientMpiId", access.getPatientMpiId(),
                "purpose", access.getPurpose().name(),
                "grantedAt", access.getGrantedAt().toString(),
                "expiresAt", access.getExpiresAt().toString());
    }

    public record BreakGlassRequest(
            @NotBlank @Size(max = 128) String patientMpiId,
            @NotNull BreakGlassPurpose purpose,
            @NotBlank @Size(min = 10, max = 2_000) String reason,
            @Min(1) @Max(60) int durationMinutes) {
    }
}
