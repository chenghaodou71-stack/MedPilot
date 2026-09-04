package com.medpilot.governance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SafetyIncidentRepository extends JpaRepository<SafetyIncident, Long> {
    Optional<SafetyIncident> findByIncidentId(String incidentId);
    List<SafetyIncident> findAllByOrderByDetectedAtDesc();
    List<SafetyIncident> findAllByReleaseIdOrderByDetectedAtDesc(String releaseId);
    long countByStatusNot(String status);
}
