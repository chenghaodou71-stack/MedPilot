package com.medpilot.clinicalreview;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ClinicalReviewRepository extends JpaRepository<ClinicalReview, Long> {

    Optional<ClinicalReview> findByReviewId(String reviewId);

    Optional<ClinicalReview> findByConsultationRecordId(Long consultationRecordId);

    List<ClinicalReview> findAllByOrderByCreatedAtDesc();

    List<ClinicalReview> findByStatusOrderByCreatedAtDesc(ClinicalReviewStatus status);
}
