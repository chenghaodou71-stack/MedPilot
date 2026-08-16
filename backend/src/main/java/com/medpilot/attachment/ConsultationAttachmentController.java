package com.medpilot.attachment;

import com.medpilot.common.ApiResponse;
import com.medpilot.user.User;
import com.medpilot.user.UserRepository;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.bind.annotation.RequestBody;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/consult/attachments")
public class ConsultationAttachmentController {

    private final ConsultationAttachmentService service;
    private final UserRepository users;

    public ConsultationAttachmentController(
            ConsultationAttachmentService service,
            UserRepository users) {
        this.service = service;
        this.users = users;
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ApiResponse<Map<String, Object>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam("session_id") String sessionId,
            Authentication authentication) {
        ConsultationAttachment attachment = service.upload(userId(authentication), sessionId, file);
        return ApiResponse.ok(payload(attachment));
    }

    @GetMapping
    public ApiResponse<List<Map<String, Object>>> list(
            @RequestParam("session_id") String sessionId,
            Authentication authentication) {
        return ApiResponse.ok(service.list(userId(authentication), sessionId).stream()
                .map(this::payload)
                .toList());
    }

    @PatchMapping("/{id}/confirm")
    public ApiResponse<Map<String, Object>> confirm(
            @PathVariable String id,
            @Valid @RequestBody ConfirmRequest request,
            Authentication authentication) {
        return ApiResponse.ok(payload(service.confirm(
                userId(authentication), id, request.draftText())));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, Authentication authentication) {
        service.delete(userId(authentication), id);
    }

    private Long userId(Authentication authentication) {
        User user = users.findByUsername(authentication.getName())
                .orElseThrow(() -> new IllegalStateException("User not found"));
        return user.getId();
    }

    private Map<String, Object> payload(ConsultationAttachment attachment) {
        return Map.ofEntries(
                Map.entry("id", attachment.getId()),
                Map.entry("sessionId", attachment.getSessionId()),
                Map.entry("originalFilename", attachment.getOriginalFilename()),
                Map.entry("mediaType", attachment.getMediaType()),
                Map.entry("sizeBytes", attachment.getSizeBytes()),
                Map.entry("kind", attachment.getKind().name()),
                Map.entry("status", attachment.getStatus().name()),
                Map.entry("extractedText", safe(attachment.getExtractedText())),
                Map.entry("draftText", safe(attachment.getDraftText())),
                Map.entry("confirmedText", attachment.getStatus() == AttachmentStatus.CONFIRMED
                        ? safe(attachment.getDraftText()) : ""),
                Map.entry("confirmationRequired", attachment.getStatus() != AttachmentStatus.CONFIRMED),
                // This flag is deliberately hard-coded false: multimodal bytes never enter diagnosis.
                Map.entry("automaticAnalysisAllowed", false),
                Map.entry("createdAt", attachment.getCreatedAt().toString()),
                Map.entry("expiresAt", attachment.getExpiresAt().toString()));
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    public record ConfirmRequest(
            @NotBlank @Size(max = 4000) String draftText) {
    }
}
