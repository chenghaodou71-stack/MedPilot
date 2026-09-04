package com.medpilot.governance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClinicalEvaluationRunRepository extends JpaRepository<ClinicalEvaluationRun, Long> {
    Optional<ClinicalEvaluationRun> findByRunId(String runId);
    List<ClinicalEvaluationRun> findAllByReleaseIdOrderByCreatedAtDesc(String releaseId);
    boolean existsByReleaseIdAndStatus(String releaseId, String status);
    long countByStatus(String status);
}
