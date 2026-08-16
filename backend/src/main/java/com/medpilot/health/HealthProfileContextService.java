package com.medpilot.health;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Resolves the smallest safe projection of a patient's opted-in profile for AI context.
 * The projection deliberately excludes the encrypted JSON envelope and unknown keys.
 */
@Service
public class HealthProfileContextService {

    private static final int MAX_FIELD_CHARS = 4_000;
    private static final List<String> ALLOWED_FIELDS = List.of(
            "allergies", "conditions", "medications", "notes");

    private final HealthProfileRepository profiles;
    private final ObjectMapper mapper;

    public HealthProfileContextService(
            HealthProfileRepository profiles,
            ObjectMapper mapper) {
        this.profiles = profiles;
        this.mapper = mapper;
    }

    /**
     * Returns an empty map for every non-eligible profile. Empty maps are omitted from the
     * outbound AI request, so revoking consent immediately stops future context injection.
     */
    public Map<String, String> resolveForUser(Long userId) {
        if (userId == null) {
            return Map.of();
        }

        try {
            HealthProfile profile = profiles.findByUserIdAndConsentGrantedTrue(userId)
                    .orElse(null);
            if (profile == null || !userId.equals(profile.getUserId())) {
                return Map.of();
            }

            JsonNode root = mapper.readTree(profile.getProfileJson());
            if (root == null || !root.isObject()) {
                return Map.of();
            }

            Map<String, String> context = new LinkedHashMap<>();
            for (String field : ALLOWED_FIELDS) {
                JsonNode value = root.get(field);
                if (value == null || !value.isTextual()) {
                    continue;
                }
                String normalized = value.textValue().trim().replaceAll("\\s+", " ");
                if (!normalized.isEmpty() && normalized.length() <= MAX_FIELD_CHARS) {
                    context.put(field, normalized);
                }
            }
            return context.isEmpty() ? Map.of() : Map.copyOf(context);
        } catch (JsonProcessingException | RuntimeException ignored) {
            // Malformed/decryption-failed PHI must fail closed without exposing details.
            return Map.of();
        }
    }
}
