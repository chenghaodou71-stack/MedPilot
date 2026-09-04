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

    @Query("SELECT r FROM ConsultationRecord r WHERE r.userId = :userId " +
           "AND (:recordId IS NULL OR r.id = :recordId) " +
           "AND (:sessionId IS NULL OR LOWER(r.sessionId) LIKE LOWER(CONCAT('%', :sessionId, '%'))) " +
           "AND (:department IS NULL OR r.department = :department) " +
           "AND (:startTime IS NULL OR r.createdAt >= :startTime) " +
           "AND (:endTime IS NULL OR r.createdAt <= :endTime) " +
           "ORDER BY r.createdAt DESC, r.id DESC")
    List<ConsultationRecord> queryByUser(
            @Param("userId") Long userId,
            @Param("recordId") Long recordId,
            @Param("sessionId") String sessionId,
            @Param("department") String department,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);

    @Query("SELECT r FROM ConsultationRecord r WHERE " +
           "(:recordId IS NULL OR r.id = :recordId) " +
           "AND (:sessionId IS NULL OR LOWER(r.sessionId) LIKE LOWER(CONCAT('%', :sessionId, '%'))) " +
           "AND (:department IS NULL OR r.department = :department) " +
           "AND (:startTime IS NULL OR r.createdAt >= :startTime) " +
           "AND (:endTime IS NULL OR r.createdAt <= :endTime) " +
           "ORDER BY r.createdAt DESC, r.id DESC")
    List<ConsultationRecord> queryAll(
            @Param("recordId") Long recordId,
            @Param("sessionId") String sessionId,
            @Param("department") String department,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);

    @Query("""
            SELECT r FROM ConsultationRecord r
            WHERE (
                r.userId = :actorUserId
                OR (
                    r.patientMpiId IS NOT NULL
                    AND r.organizationCode = :organizationCode
                    AND (
                        EXISTS (
                            SELECT relationship.id FROM PatientCareRelationship relationship
                            WHERE relationship.clinicianUserId = :actorUserId
                              AND relationship.patientMpiId = r.patientMpiId
                              AND relationship.organizationCode = r.organizationCode
                              AND relationship.campusCode = r.campusCode
                              AND relationship.departmentCode = r.encounterDepartmentCode
                              AND relationship.active = true
                              AND relationship.validFrom <= :now
                              AND (relationship.validUntil IS NULL OR relationship.validUntil > :now)
                        )
                        OR EXISTS (
                            SELECT access.id FROM BreakGlassAccess access
                            WHERE access.clinicianUserId = :actorUserId
                              AND access.patientMpiId = r.patientMpiId
                              AND access.organizationCode = r.organizationCode
                              AND access.campusCode = r.campusCode
                              AND access.departmentCode = r.encounterDepartmentCode
                              AND access.revokedAt IS NULL
                              AND access.grantedAt <= :now
                              AND access.expiresAt > :now
                        )
                    )
                )
            )
            AND (:recordId IS NULL OR r.id = :recordId)
            AND (:sessionId IS NULL OR LOWER(r.sessionId) LIKE LOWER(CONCAT('%', :sessionId, '%')))
            AND (:department IS NULL OR r.department = :department)
            AND (:startTime IS NULL OR r.createdAt >= :startTime)
            AND (:endTime IS NULL OR r.createdAt <= :endTime)
            ORDER BY r.createdAt DESC, r.id DESC
            """)
    List<ConsultationRecord> queryReadableForActor(
            @Param("actorUserId") Long actorUserId,
            @Param("organizationCode") String organizationCode,
            @Param("now") Instant now,
            @Param("recordId") Long recordId,
            @Param("sessionId") String sessionId,
            @Param("department") String department,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);
}
