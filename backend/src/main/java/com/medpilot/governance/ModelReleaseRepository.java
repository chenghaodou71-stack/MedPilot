package com.medpilot.governance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ModelReleaseRepository extends JpaRepository<ModelRelease, Long> {
    Optional<ModelRelease> findByReleaseId(String releaseId);
    List<ModelRelease> findAllByOrderByCreatedAtDesc();
}
