package com.medpilot.hospital;

import com.medpilot.auth.JwtAuthFilter;
import com.medpilot.consult.ConsultationRecord;
import com.medpilot.consult.ConsultationRecordRepository;
import com.medpilot.user.Role;
import com.medpilot.user.User;
import com.medpilot.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.util.UUID;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HospitalRecordAccessIntegrationTest {

    private static final String ORGANIZATION = "HOSP-A";
    private static final String CAMPUS = "MAIN";
    private static final String DEPARTMENT = "EMERGENCY";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PatientRepository patients;
    @Autowired PatientCareRelationshipRepository relationships;
    @Autowired ConsultationRecordRepository records;
    @Autowired PasswordEncoder passwordEncoder;

    private User doctor;
    private User patientUser;
    private Patient patient;
    private ConsultationRecord record;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        doctor = new User("doctor-" + suffix, passwordEncoder.encode("doctor-pass-123"), Role.DOCTOR);
        doctor.setHospitalStaffProfile("EMP-" + suffix, ORGANIZATION, CAMPUS, DEPARTMENT);
        doctor.setMfaAssuranceLevel(2);
        doctor = users.saveAndFlush(doctor);

        patient = patients.saveAndFlush(new Patient("MPI-" + suffix, ORGANIZATION, "HIS"));
        patientUser = new User("patient-" + suffix, passwordEncoder.encode("patient-pass-123"), Role.USER);
        patientUser.setPatientIdentity(patient.getMpiId(), ORGANIZATION);
        patientUser = users.saveAndFlush(patientUser);

        record = new ConsultationRecord(patientUser.getId(), "session-" + suffix);
        record.setPatientContext(
                patient.getMpiId(), "ENC-" + suffix, ORGANIZATION, CAMPUS, DEPARTMENT);
        record.setSymptoms("胸痛");
        records.saveAndFlush(record);
    }

    @Test
    void clinicianWithoutActiveCareRelationshipCannotReadPatientRecord() throws Exception {
        mvc.perform(get("/api/records/" + record.getId()).cookie(login(doctor, "doctor-pass-123")))
                .andExpect(status().isForbidden());
    }

    @Test
    void activeCareRelationshipWithMatchingOrganizationAndEncounterBoundaryAllowsRead() throws Exception {
        relationships.saveAndFlush(new PatientCareRelationship(
                patient.getMpiId(), doctor.getId(), ORGANIZATION, CAMPUS, DEPARTMENT,
                CareRelationshipType.ATTENDING, "HIS", Instant.now().minusSeconds(60), null));

        Cookie doctorCookie = login(doctor, "doctor-pass-123");
        mvc.perform(get("/api/records/" + record.getId()).cookie(doctorCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.symptoms").value("胸痛"));
        mvc.perform(get("/api/records").cookie(doctorCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void breakGlassIsTimeBoundAndGrantsOnlyTheRequestingClinicianReadAccess() throws Exception {
        Cookie doctorCookie = login(doctor, "doctor-pass-123");
        mvc.perform(post("/api/access/break-glass")
                        .with(csrf())
                        .cookie(doctorCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"patientMpiId":"%s","purpose":"EMERGENCY_TREATMENT",
                                "reason":"患者无法表达病史，需要紧急处置","durationMinutes":15}
                                """.formatted(patient.getMpiId())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.accessId").isNotEmpty())
                .andExpect(jsonPath("$.data.patientMpiId").value(patient.getMpiId()));

        mvc.perform(get("/api/records/" + record.getId()).cookie(doctorCookie))
                .andExpect(status().isOk());
    }

    @Test
    void globalAdministratorDoesNotBypassPatientRelationshipAuthorization() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        User administrator = users.saveAndFlush(new User(
                "admin-" + suffix, passwordEncoder.encode("admin-pass-123"), Role.ADMIN));

        mvc.perform(get("/api/records/" + record.getId()).cookie(login(administrator, "admin-pass-123")))
                .andExpect(status().isForbidden());
    }

    private Cookie login(User user, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + user.getUsername()
                                + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie(JwtAuthFilter.AUTH_COOKIE_NAME);
    }
}
