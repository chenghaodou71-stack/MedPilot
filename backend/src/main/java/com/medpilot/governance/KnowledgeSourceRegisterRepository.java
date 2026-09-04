package com.medpilot.governance;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface KnowledgeSourceRegisterRepository extends JpaRepository<KnowledgeSourceRegister, Long> {
    Optional<KnowledgeSourceRegister> findBySourceId(String sourceId);
    List<KnowledgeSourceRegister> findAllByOrderByCreatedAtDesc();
    long countByReviewStatus(String reviewStatus);
}
