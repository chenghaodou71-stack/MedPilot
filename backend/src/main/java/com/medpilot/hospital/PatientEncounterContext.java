package com.medpilot.hospital;

/**
 * Immutable, already-authorized patient/encounter projection attached to a
 * consultation. Direct patient identity remains in the hospital MPI; this
 * value object carries only the references needed for access control and
 * record partitioning.
 */
public record PatientEncounterContext(
        String patientMpiId,
        String encounterNumber,
        String organizationCode,
        String campusCode,
        String departmentCode) {

    public PatientEncounterContext {
        patientMpiId = required(patientMpiId, 128, "patient MPI id");
        encounterNumber = required(encounterNumber, 128, "encounter number");
        organizationCode = required(organizationCode, 64, "organization code");
        campusCode = required(campusCode, 64, "campus code");
        departmentCode = required(departmentCode, 64, "encounter department code");
    }

    private static String required(String value, int maxLength, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is required and too long");
        }
        return normalized;
    }
}
