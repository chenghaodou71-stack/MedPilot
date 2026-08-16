package com.medpilot.consult;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Locale;
import java.util.UUID;

@Service
public class SessionOwnershipService {

    private final ConsultationSessionRepository sessions;

    public SessionOwnershipService(ConsultationSessionRepository sessions) {
        this.sessions = sessions;
    }

    @Transactional
    public ConsultationSession claim(String sessionId, Long userId) {
        String canonicalId = canonicalUuid(sessionId);
        if (userId == null) {
            throw new IllegalArgumentException("user_id is required");
        }

        ConsultationSession session = sessions.findBySessionId(canonicalId).orElse(null);
        if (session == null) {
            return sessions.save(new ConsultationSession(canonicalId, userId));
        }
        if (!session.getUserId().equals(userId)) {
            throw new SecurityException("session belongs to another user");
        }
        session.touch();
        return session;
    }

    static String canonicalUuid(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("session_id is required");
        }
        try {
            String normalized = value.toLowerCase(Locale.ROOT);
            UUID parsed = UUID.fromString(normalized);
            if (!parsed.toString().equals(normalized)) {
                throw new IllegalArgumentException("session_id must be a canonical UUID");
            }
            return normalized;
        } catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("session_id must be a canonical UUID");
        }
    }
}
