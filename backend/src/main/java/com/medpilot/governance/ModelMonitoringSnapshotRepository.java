package com.medpilot.governance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelMonitoringSnapshotRepository extends JpaRepository<ModelMonitoringSnapshot, Long> {
    Optional<ModelMonitoringSnapshot> findBySnapshotId(String snapshotId);
    List<ModelMonitoringSnapshot> findAllByReleaseIdOrderByObservedAtDesc(String releaseId);
}
