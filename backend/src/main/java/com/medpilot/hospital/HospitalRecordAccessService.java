package com.medpilot.hospital;

import com.medpilot.consult.ConsultationRecord;
import com.medpilot.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;

/**
 * Central record-access policy. Clinical data is never released on a global
 * application role alone; staff must have a current care relationship or a
 * time-bounded emergency override matching the record's organization scope.
 */
@Service
public class HospitalRecordAccessService {

    private final PatientCareRelationshipRepository careRelationships;
    private final BreakGlassAccessRepository breakGlassAccesses;

    public HospitalRecordAccessService(
            PatientCareRelationshipRepository careRelationships,
            BreakGlassAccessRepository breakGlassAccesses) {
        this.careRelationships = careRelationships;
        this.breakGlassAccesses = breakGlassAccesses;
    }

    @Transactional(readOnly = true)
    public boolean canRead(User actor, ConsultationRecord record) {
        if (actor == null || record == null || !actor.isLoginEligibleAt(Instant.now())) return false;
        if (record.getUserId().equals(actor.getId())) return true;
        if (!record.hasPatientContext() || !actor.hasHospitalStaffProfile()) return false;

        Instant now = Instant.now();
        if (!sameOrganization(actor, record)) return false;
        return !careRelationships.findActiveForRecord(
                        actor.getId(),
                        record.getPatientMpiId(),
                        record.getOrganizationCode(),
                        record.getCampusCode(),
                        record.getEncounterDepartmentCode(),
                        now)
                .isEmpty()
                || !breakGlassAccesses.findActiveForRecord(
                        actor.getId(),
                        record.getPatientMpiId(),
                        record.getOrganizationCode(),
                        record.getCampusCode(),
                        record.getEncounterDepartmentCode(),
                        now)
                .isEmpty();
    }

    @Transactional(readOnly = true)
    public List<ConsultationRecord> filterReadable(User actor, List<ConsultationRecord> candidates) {
        if (actor == null || candidates == null || candidates.isEmpty()) return List.of();
        return candidates.stream()
                .filter(record -> canRead(actor, record))
                .toList();
    }

    private static boolean sameOrganization(User actor, ConsultationRecord record) {
        return actor.getOrganizationCode() != null
                && actor.getOrganizationCode().equals(record.getOrganizationCode());
    }
}
