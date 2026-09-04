package com.medpilot.hospital;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface BreakGlassAccessRepository extends JpaRepository<BreakGlassAccess, Long> {

    @Query("""
            select access from BreakGlassAccess access
            where access.clinicianUserId = :clinicianUserId
              and access.patientMpiId = :patientMpiId
              and access.organizationCode = :organizationCode
              and access.campusCode = :campusCode
              and access.departmentCode = :departmentCode
              and access.revokedAt is null
              and access.grantedAt <= :now
              and access.expiresAt > :now
            """)
    List<BreakGlassAccess> findActiveForRecord(
            @Param("clinicianUserId") Long clinicianUserId,
            @Param("patientMpiId") String patientMpiId,
            @Param("organizationCode") String organizationCode,
            @Param("campusCode") String campusCode,
            @Param("departmentCode") String departmentCode,
            @Param("now") Instant now);

    @Query("""
            select access.patientMpiId from BreakGlassAccess access
            where access.clinicianUserId = :clinicianUserId
              and access.revokedAt is null
              and access.grantedAt <= :now
              and access.expiresAt > :now
            """)
    List<String> findActivePatientMpiIds(
            @Param("clinicianUserId") Long clinicianUserId,
            @Param("now") Instant now);

    List<BreakGlassAccess> findByClinicianUserIdOrderByGrantedAtDesc(Long clinicianUserId);
}
