package com.medpilot.health;

import com.medpilot.auth.JwtAuthFilter;
import com.medpilot.consult.ConsultationRecord;
import com.medpilot.consult.ConsultationRecordRepository;
import com.medpilot.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthProfileIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ConsultationRecordRepository records;

    @Autowired
    HealthProfileRepository profiles;

    @Autowired
    FollowUpTaskRepository followUps;

    @Autowired
    JdbcTemplate jdbc;

    @Autowired
    UserRepository users;

    private Cookie userCookie;

    @BeforeEach
    void setup() throws Exception {
        followUps.deleteAll();
        profiles.deleteAll();
        records.deleteAll();
        userCookie = login("user", "user123");
    }

    @Test
    void profileIsEncryptedAtRestAndRoundTripsThroughApi() throws Exception {
        mvc.perform(put("/api/profile")
                        .cookie(userCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allergies":"青霉素","conditions":"哮喘","medications":"已由医生开具","notes":"运动后注意观察","consentGranted":true}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.allergies").value("青霉素"))
                .andExpect(jsonPath("$.data.consentGranted").value(true));

        Long userId = users.findByUsername("user").orElseThrow().getId();
        String storedCiphertext = jdbc.queryForObject(
                "select profile_json from health_profiles where user_id = ?", String.class, userId);
        org.assertj.core.api.Assertions.assertThat(storedCiphertext).doesNotContain("青霉素");

        mvc.perform(get("/api/profile").cookie(userCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.conditions").value("哮喘"));
    }

    @Test
    void timelineAndFollowUpTasksAreScopedToCurrentUser() throws Exception {
        ConsultationRecord record = new ConsultationRecord(2L, "health-session");
        record.setSymptoms("咳嗽");
        record.setRiskLevel("中");
        Long recordId = records.save(record).getId();

        mvc.perform(get("/api/profile/timeline").cookie(userCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].symptoms").value("咳嗽"));

        mvc.perform(post("/api/profile/follow-ups")
                        .cookie(userCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"复查咳嗽变化","dueAt":"2030-01-02T10:00:00Z","recordId":%d,"notes":"如加重请提前就医"}
                                """.formatted(recordId)
                                ))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("OPEN"));

        mvc.perform(get("/api/profile/follow-ups").cookie(userCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].title").value("复查咳嗽变化"));
    }

    @Test
    void followUpStatusCanBeCompletedByOwner() throws Exception {
        var response = mvc.perform(post("/api/profile/follow-ups")
                        .cookie(userCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"title":"复诊","dueAt":"2030-01-02T10:00:00Z"}
                                """))
                .andExpect(status().isCreated())
                .andReturn();
        String id = com.fasterxml.jackson.databind.json.JsonMapper.builder().build()
                .readTree(response.getResponse().getContentAsString()).path("data").path("id").asText();

        mvc.perform(patch("/api/profile/follow-ups/" + id)
                        .cookie(userCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"COMPLETED\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("COMPLETED"));
    }

    @Test
    void dueFollowUpsOnlyExposeOpenTasksAlreadyDueForCurrentUser() throws Exception {
        Instant now = Instant.now();
        followUps.save(new FollowUpTask(
                2L, null, "已到期复查", "", now.minusSeconds(60)));
        followUps.save(new FollowUpTask(
                2L, null, "未来复查", "", now.plusSeconds(3600)));
        FollowUpTask completed = followUps.save(new FollowUpTask(
                2L, null, "已完成复查", "", now.minusSeconds(120)));
        completed.setStatus(FollowUpTask.Status.COMPLETED);
        followUps.save(completed);
        FollowUpTask cancelled = followUps.save(new FollowUpTask(
                2L, null, "已取消复查", "", now.minusSeconds(120)));
        cancelled.setStatus(FollowUpTask.Status.CANCELLED);
        followUps.save(cancelled);

        mvc.perform(get("/api/profile/follow-ups/due").cookie(userCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data").isArray())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].title").value("已到期复查"))
                .andExpect(jsonPath("$.data[0].status").value("OPEN"));
    }

    private Cookie login(String username, String password) throws Exception {
        var response = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return response.getResponse().getCookie(JwtAuthFilter.AUTH_COOKIE_NAME);
    }
}
