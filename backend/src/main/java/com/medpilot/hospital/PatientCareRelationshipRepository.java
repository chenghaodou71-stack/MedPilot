package com.medpilot.hospital;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface PatientCareRelationshipRepository extends JpaRepository<PatientCareRelationship, Long> {

    @Query("""
            select relationship from PatientCareRelationship relationship
            where relationship.clinicianUserId = :clinicianUserId
              and relationship.patientMpiId = :patientMpiId
              and relationship.organizationCode = :organizationCode
              and relationship.campusCode = :campusCode
              and relationship.departmentCode = :departmentCode
              and relationship.active = true
              and relationship.validFrom <= :now
              and (relationship.validUntil is null or relationship.validUntil > :now)
            """)
    List<PatientCareRelationship> findActiveForRecord(
            @Param("clinicianUserId") Long clinicianUserId,
            @Param("patientMpiId") String patientMpiId,
            @Param("organizationCode") String organizationCode,
            @Param("campusCode") String campusCode,
            @Param("departmentCode") String departmentCode,
            @Param("now") Instant now);

    @Query("""
            select relationship.patientMpiId from PatientCareRelationship relationship
            where relationship.clinicianUserId = :clinicianUserId
              and relationship.active = true
              and relationship.validFrom <= :now
              and (relationship.validUntil is null or relationship.validUntil > :now)
            """)
    List<String> findActivePatientMpiIds(
            @Param("clinicianUserId") Long clinicianUserId,
            @Param("now") Instant now);
}
