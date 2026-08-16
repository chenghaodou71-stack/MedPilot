package com.medpilot.health;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface HealthProfileRepository extends JpaRepository<HealthProfile, Long> {
    Optional<HealthProfile> findByUserId(Long userId);
    Optional<HealthProfile> findByUserIdAndConsentGrantedTrue(Long userId);
}
