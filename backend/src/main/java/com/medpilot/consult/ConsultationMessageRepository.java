package com.medpilot.consult;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface ConsultationMessageRepository extends JpaRepository<ConsultationMessage, Long> {

    List<ConsultationMessage> findBySessionIdAndUserIdOrderByCreatedAtAscIdAsc(
            String sessionId, Long userId);
}
