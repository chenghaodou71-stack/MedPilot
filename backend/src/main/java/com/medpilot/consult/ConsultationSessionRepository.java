package com.medpilot.consult;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConsultationSessionRepository extends JpaRepository<ConsultationSession, Long> {
    Optional<ConsultationSession> findBySessionId(String sessionId);
}
