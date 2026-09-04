package com.medpilot.clinicalreview;

import com.medpilot.auth.JwtAuthFilter;
import com.medpilot.consult.ConsultationRecord;
import com.medpilot.consult.ConsultationRecordRepository;
import com.medpilot.hospital.CareRelationshipType;
import com.medpilot.hospital.Patient;
import com.medpilot.hospital.PatientCareRelationship;
import com.medpilot.hospital.PatientCareRelationshipRepository;
import com.medpilot.hospital.PatientRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClinicalReviewIntegrationTest {

    private static final String ORG = "HOSP-REVIEW";
    private static final String CAMPUS = "MAIN";
    private static final String DEPARTMENT = "EMERGENCY";

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PatientRepository patients;
    @Autowired PatientCareRelationshipRepository relationships;
    @Autowired ConsultationRecordRepository records;
    @Autowired ClinicalReviewRepository reviews;
    @Autowired PasswordEncoder passwordEncoder;

    private User doctor;
    private User admin;
    private User patientUser;
    private ConsultationRecord record;

    @BeforeEach
    void setUp() {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        doctor = new User("review-doctor-" + suffix, passwordEncoder.encode("doctor-pass-123"), Role.DOCTOR);
        doctor.setHospitalStaffProfile("EMP-" + suffix, ORG, CAMPUS, DEPARTMENT);
        doctor.setMfaAssuranceLevel(2);
        doctor = users.saveAndFlush(doctor);

        admin = new User("review-admin-" + suffix, passwordEncoder.encode("admin-pass-123"), Role.ADMIN);
        admin = users.saveAndFlush(admin);

        Patient patient = patients.saveAndFlush(new Patient("MPI-" + suffix, ORG, "HIS"));
        patientUser = new User("review-patient-" + suffix, passwordEncoder.encode("patient-pass-123"), Role.USER);
        patientUser.setPatientIdentity(patient.getMpiId(), ORG);
        patientUser = users.saveAndFlush(patientUser);

        record = new ConsultationRecord(patientUser.getId(), "session-" + suffix);
        record.setPatientContext(patient.getMpiId(), "ENC-" + suffix, ORG, CAMPUS, DEPARTMENT);
        record.setSymptoms("胸痛");
        record.setDepartment("心血管内科");
        record.setRiskLevel("中");
        record.setUrgency("建议尽快就诊");
        records.saveAndFlush(record);
        relationships.saveAndFlush(new PatientCareRelationship(
                patient.getMpiId(), doctor.getId(), ORG, CAMPUS, DEPARTMENT,
                CareRelationshipType.ATTENDING, "HIS", Instant.now().minusSeconds(60), null));
        reviews.saveAndFlush(new ClinicalReview(record));
    }

    @Test
    void claimAndModifyKeepsOriginalAiRecordUntouched() throws Exception {
        Cookie doctorCookie = login(doctor, "doctor-pass-123");
        String reviewId = reviews.findByConsultationRecordId(record.getId()).orElseThrow().getId().toString();

        mvc.perform(get("/api/clinical-reviews").cookie(doctorCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].status").value("PENDING_REVIEW"));
        mvc.perform(post("/api/clinical-reviews/" + reviewId + "/claim")
                        .with(csrf()).cookie(doctorCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("IN_REVIEW"))
                .andExpect(jsonPath("$.data.reviewerEmployeeNumber").value(doctor.getEmployeeNumber()));
        mvc.perform(post("/api/clinical-reviews/" + reviewId + "/decision")
                        .with(csrf()).cookie(doctorCookie)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"decision":"MODIFY","finalDepartment":"急诊医学科",
                                "finalRiskLevel":"高","finalUrgency":"立即急诊评估",
                                "reason":"结合生命体征和红旗症状调整分诊"}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CLINICIAN_MODIFIED"))
                .andExpect(jsonPath("$.data.finalDepartment").value("急诊医学科"));

        ConsultationRecord unchanged = records.findById(record.getId()).orElseThrow();
        assertThat(unchanged.getDepartment()).isEqualTo("心血管内科");
        assertThat(unchanged.getRiskLevel()).isEqualTo("中");
        assertThat(unchanged.getUrgency()).isEqualTo("建议尽快就诊");
    }

    @Test
    void reviewerWithoutCareRelationshipCannotDiscoverQueue() throws Exception {
        String suffix = UUID.randomUUID().toString().replace("-", "");
        User reviewer = new User("reviewer-" + suffix, passwordEncoder.encode("reviewer-pass-123"), Role.REVIEWER);
        reviewer.setHospitalStaffProfile("EMP-R-" + suffix, ORG, CAMPUS, DEPARTMENT);
        reviewer.setMfaAssuranceLevel(2);
        reviewer = users.saveAndFlush(reviewer);

        mvc.perform(get("/api/clinical-reviews").cookie(login(reviewer, "reviewer-pass-123")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(0));
    }

    @Test
    void patientRoleCannotOperateClinicalReviewQueue() throws Exception {
        mvc.perform(get("/api/clinical-reviews")
                        .cookie(login(patientUser, "patient-pass-123")))
                .andExpect(status().isForbidden());
    }

    @Test
    void administratorRoleCannotOperateClinicalReviewQueue() throws Exception {
        mvc.perform(get("/api/clinical-reviews")
                        .cookie(login(admin, "admin-pass-123")))
                .andExpect(status().isForbidden());
    }

    @Test
    void highRiskResultStartsEmergencyEscalated() {
        record.setRiskLevel("高");
        record.setUrgency("立即急诊");
        records.saveAndFlush(record);
        ClinicalReview emergency = new ClinicalReview(record);
        assertThat(emergency.getStatus()).isEqualTo(ClinicalReviewStatus.EMERGENCY_ESCALATED);
        assertThat(emergency.getDecision()).isEqualTo(ClinicalReviewDecision.ESCALATE);
        assertThat(emergency.getEmergencyEscalatedAt()).isNotNull();
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
