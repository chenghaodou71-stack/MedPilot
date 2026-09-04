package com.medpilot.audit;

import com.medpilot.auth.JwtAuthFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditLogIntegrationTest {
    @Autowired MockMvc mvc;
    @Autowired AuditLogRepository logs;
    private Cookie admin;
    private Cookie user;

    @BeforeEach
    void setup() throws Exception {
        logs.deleteAll();
        admin = login("admin", "admin123");
        user = login("user", "user123");
        logs.deleteAll();
    }

    @Test
    void stateChangingRequestIsAuditedWithoutBody() throws Exception {
        mvc.perform(put("/api/profile").cookie(user).with(csrf())
                        .header("X-Request-Id", "demo-request-42")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"allergies\":\"private allergy\",\"conditions\":\"private condition\",\"consentGranted\":true}"))
                .andExpect(status().isOk());

        AuditLog event = logs.findAll().stream()
                .filter(item -> "demo-request-42".equals(item.getRequestId()))
                .findFirst().orElseThrow();
        assertThat(event.getActorUsername()).isEqualTo("user");
        assertThat(event.getMethod()).isEqualTo("PUT");
        assertThat(event.getAction()).isEqualTo("/api/profile");
        assertThat(event.isSuccess()).isTrue();
        assertThat(event.getAction()).doesNotContain("private allergy");
    }

    @Test
    void auditQueriesAreRestrictedAndExportIsDeidentified() throws Exception {
        mvc.perform(get("/api/audit/logs").cookie(user))
                .andExpect(status().isForbidden());
        mvc.perform(get("/api/audit/logs").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").exists());
        mvc.perform(get("/api/audit/export").cookie(admin))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith("text/csv"))
                .andExpect(content().string(org.hamcrest.Matchers.startsWith(
                        "event_id,actor_role,action,status,success,created_at,duration_ms\n")))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString("private allergy"))))
                .andExpect(content().string(org.hamcrest.Matchers.not(
                        org.hamcrest.Matchers.containsString(",user,"))));
    }

    @Test
    void statusGroupFiltersUseHttpStatusRanges() throws Exception {
        saveAuditEvent(204);
        saveAuditEvent(302);
        saveAuditEvent(404);
        saveAuditEvent(503);

        mvc.perform(get("/api/audit/logs")
                        .cookie(admin)
                        .param("statusGroup", "success"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(2))
                .andExpect(jsonPath("$.data[0].status").value(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(204), org.hamcrest.Matchers.is(302))))
                .andExpect(jsonPath("$.data[1].status").value(org.hamcrest.Matchers.anyOf(
                        org.hamcrest.Matchers.is(204), org.hamcrest.Matchers.is(302))));

        mvc.perform(get("/api/audit/logs")
                        .cookie(admin)
                        .param("statusGroup", "client_error"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].status").value(404));

        mvc.perform(get("/api/audit/logs")
                        .cookie(admin)
                        .param("statusGroup", "server_error"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.data[0].status").value(503));
    }

    @Test
    void statusGroupRejectsUnknownValuesAndConflictingExactStatus() throws Exception {
        mvc.perform(get("/api/audit/logs")
                        .cookie(admin)
                        .param("statusGroup", "unknown"))
                .andExpect(status().isBadRequest());

        mvc.perform(get("/api/audit/logs")
                        .cookie(admin)
                        .param("status", "200")
                        .param("statusGroup", "success"))
                .andExpect(status().isBadRequest());
    }

    private void saveAuditEvent(int status) {
        logs.save(new AuditLog(
                UUID.randomUUID().toString(), "range-tester", "ROLE_ADMIN",
                "GET", "/api/range-test", status, status < 400,
                null, "", 1));
    }

    private Cookie login(String username, String password) throws Exception {
        var response = mvc.perform(post("/api/auth/login").with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk()).andReturn();
        return response.getResponse().getCookie(JwtAuthFilter.AUTH_COOKIE_NAME);
    }
}
