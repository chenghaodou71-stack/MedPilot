package com.medpilot.consult;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface ConsultationRecordRepository extends JpaRepository<ConsultationRecord, Long> {

    Optional<ConsultationRecord> findByTraceId(String traceId);

    List<ConsultationRecord> findByUserIdOrderByCreatedAtDesc(Long userId);

    @Query("SELECT r FROM ConsultationRecord r WHERE r.userId = :userId " +
           "AND (:department IS NULL OR r.department = :department) " +
           "AND (:startTime IS NULL OR r.createdAt >= :startTime) " +
           "ORDER BY r.createdAt DESC")
    List<ConsultationRecord> searchByUser(
            @Param("userId") Long userId,
            @Param("department") String department,
            @Param("startTime") Instant startTime
    );

    @Query("SELECT r FROM ConsultationRecord r WHERE " +
           "(:department IS NULL OR r.department = :department) " +
           "AND (:startTime IS NULL OR r.createdAt >= :startTime) " +
           "ORDER BY r.createdAt DESC")
    List<ConsultationRecord> searchAll(
            @Param("department") String department,
            @Param("startTime") Instant startTime
    );
}
