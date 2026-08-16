package com.medpilot.consult;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ConsultationTraceRepository extends JpaRepository<ConsultationTrace, Long> {
    Optional<ConsultationTrace> findByTraceId(String traceId);
    boolean existsByTraceId(String traceId);
}
