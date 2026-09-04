package com.medpilot.hospital;

import com.medpilot.user.User;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;

/**
 * Resolves and authorizes the MPI/encounter references supplied to a
 * consultation. The caller never gets to persist an arbitrary set of scope
 * fields: every field is copied from the trusted MPI/EMR projection and the
 * actor must have either a current care relationship or a break-glass grant.
 */
@Service
public class HospitalPatientContextService {

    private final PatientRepository patients;
    private final PatientEncounterRepository encounters;
    private final PatientCareRelationshipRepository careRelationships;
    private final BreakGlassAccessRepository breakGlassAccesses;
    private final boolean clinicalMode;

    public HospitalPatientContextService(
            PatientRepository patients,
            PatientEncounterRepository encounters,
            PatientCareRelationshipRepository careRelationships,
            BreakGlassAccessRepository breakGlassAccesses,
            @Value("${medpilot.runtime-mode:development}") String runtimeMode) {
        this.patients = patients;
        this.encounters = encounters;
        this.careRelationships = careRelationships;
        this.breakGlassAccesses = breakGlassAccesses;
        String mode = runtimeMode == null ? "development" : runtimeMode.strip().toLowerCase();
        this.clinicalMode = "clinical".equals(mode) || "production".equals(mode);
    }

    /**
     * Returns an empty context only for local development/test requests that
     * do not identify a patient. A clinical/production request must identify
     * an active encounter and pass the relationship check.
     */
    @Transactional(readOnly = true)
    public Optional<PatientEncounterContext> resolve(
            User actor, String requestedMpiId, String requestedEncounterNumber) {
        if (actor == null || actor.getId() == null || !actor.isLoginEligibleAt(Instant.now())) {
            throw new SecurityException("authenticated hospital identity is required");
        }

        String mpi = normalize(requestedMpiId);
        String encounterNumber = normalize(requestedEncounterNumber);
        if (mpi.isEmpty() && actor.getPatientMpiId() != null) {
            mpi = actor.getPatientMpiId().strip();
        }
        if (mpi.isEmpty() && encounterNumber.isEmpty()) {
            if (clinicalMode) {
                throw new IllegalArgumentException(
                        "patient_mpi_id and encounter_number are required in clinical mode");
            }
            return Optional.empty();
        }
        if (mpi.isEmpty() || encounterNumber.isEmpty()) {
            throw new IllegalArgumentException(
                    "patient_mpi_id and encounter_number must be supplied together");
        }

        Patient patient = patients.findByMpiIdAndActiveTrue(mpi)
                .orElseThrow(() -> new IllegalArgumentException("active patient MPI was not found"));
        PatientEncounter encounter = encounters
                .findByOrganizationCodeAndEncounterNumber(patient.getOrganizationCode(), encounterNumber)
                .orElseThrow(() -> new IllegalArgumentException("active encounter was not found"));
        if (!patient.getId().equals(encounter.getPatientId())
                || !patient.getOrganizationCode().equals(encounter.getOrganizationCode())
                || !encounter.isOpenAt(Instant.now())) {
            throw new SecurityException("encounter is not an active encounter for the patient");
        }

        if (actor.getPatientMpiId() != null && !mpi.equals(actor.getPatientMpiId())) {
            throw new SecurityException("patient identity does not match the requested MPI");
        }
        if (actor.getEmployeeNumber() == null || actor.getEmployeeNumber().isBlank()) {
            // A patient may access their own encounter; staff must pass the
            // relationship/break-glass check below.
            if (!mpi.equals(actor.getPatientMpiId())
                    || actor.getOrganizationCode() == null
                    || !actor.getOrganizationCode().equals(patient.getOrganizationCode())) {
                throw new SecurityException("hospital staff identity or patient identity is required");
            }
        } else {
            if (!actor.hasHospitalStaffProfile()
                    || !actor.getOrganizationCode().equals(encounter.getOrganizationCode())
                    || !actor.getCampusCode().equals(encounter.getCampusCode())) {
                throw new SecurityException("actor is outside the encounter organization scope");
            }
            Instant now = Instant.now();
            boolean related = !careRelationships.findActiveForRecord(
                    actor.getId(), mpi, encounter.getOrganizationCode(), encounter.getCampusCode(),
                    encounter.getDepartmentCode(), now).isEmpty();
            boolean breakGlass = !breakGlassAccesses.findActiveForRecord(
                    actor.getId(), mpi, encounter.getOrganizationCode(), encounter.getCampusCode(),
                    encounter.getDepartmentCode(), now).isEmpty();
            if (!related && !breakGlass) {
                throw new SecurityException("actor has no active medical relationship with the encounter");
            }
        }

        return Optional.of(new PatientEncounterContext(
                patient.getMpiId(), encounter.getEncounterNumber(),
                encounter.getOrganizationCode(), encounter.getCampusCode(),
                encounter.getDepartmentCode()));
    }

    private static String normalize(String value) {
        return value == null ? "" : value.strip();
    }
}
