package com.medpilot.governance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface RollbackDrillRunRepository extends JpaRepository<RollbackDrillRun, Long> {
    Optional<RollbackDrillRun> findByDrillId(String drillId);
    List<RollbackDrillRun> findAllByReleaseIdOrderByDrilledAtDesc(String releaseId);
    boolean existsByReleaseIdAndStatus(String releaseId, String status);
}
