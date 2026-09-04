package com.medpilot.governance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RedTeamTestRunRepository extends JpaRepository<RedTeamTestRun, Long> {
    Optional<RedTeamTestRun> findByTestId(String testId);
    List<RedTeamTestRun> findAllByReleaseIdOrderByExecutedAtDesc(String releaseId);
    boolean existsByReleaseIdAndStatus(String releaseId, String status);
    long countByStatus(String status);
}
