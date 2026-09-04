package com.medpilot.hospital;

import com.medpilot.user.User;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** Creates narrowly-scoped emergency access grants after local policy checks. */
@Service
public class BreakGlassAccessService {

    private static final int MIN_DURATION_MINUTES = 1;
    private static final int MAX_DURATION_MINUTES = 60;

    private final PatientRepository patients;
    private final BreakGlassAccessRepository accesses;

    public BreakGlassAccessService(PatientRepository patients, BreakGlassAccessRepository accesses) {
        this.patients = patients;
        this.accesses = accesses;
    }

    @Transactional
    public BreakGlassAccess grant(
            User clinician,
            String patientMpiId,
            BreakGlassPurpose purpose,
            String reason,
            int durationMinutes) {
        if (clinician == null || !clinician.hasHospitalStaffProfile()) {
            throw new SecurityException("only a hospital staff account can request emergency access");
        }
        if (clinician.getMfaAssuranceLevel() < 2) {
            throw new SecurityException("emergency access requires MFA assurance level 2 or higher");
        }
        if (durationMinutes < MIN_DURATION_MINUTES || durationMinutes > MAX_DURATION_MINUTES) {
            throw new IllegalArgumentException("break-glass duration must be between 1 and 60 minutes");
        }
        String mpiId = requiredCode(patientMpiId, 128, "patient MPI id");
        Patient patient = patients.findByMpiIdAndActiveTrue(mpiId)
                .orElseThrow(() -> new IllegalArgumentException("active patient MPI was not found"));
        if (!clinician.getOrganizationCode().equals(patient.getOrganizationCode())) {
            throw new SecurityException("break-glass access cannot cross organization boundaries");
        }

        Instant grantedAt = Instant.now();
        BreakGlassAccess access = new BreakGlassAccess(
                clinician.getId(),
                patient.getMpiId(),
                clinician.getOrganizationCode(),
                clinician.getCampusCode(),
                clinician.getDepartmentCode(),
                purpose,
                reason,
                grantedAt,
                grantedAt.plus(Duration.ofMinutes(durationMinutes)));
        return accesses.save(access);
    }

    @Transactional(readOnly = true)
    public List<BreakGlassAccess> activeForClinician(User clinician) {
        if (clinician == null) return List.of();
        Instant now = Instant.now();
        return accesses.findByClinicianUserIdOrderByGrantedAtDesc(clinician.getId()).stream()
                .filter(access -> access.isActiveAt(now))
                .toList();
    }

    private static String requiredCode(String value, int maxLength, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > maxLength) {
            throw new IllegalArgumentException(field + " is required and too long");
        }
        return normalized;
    }
}
