package com.medpilot.monitor;

import com.medpilot.auth.JwtAuthFilter;
import com.medpilot.consult.ConsultationEventAccumulator;
import com.medpilot.consult.ConsultationPersistenceService;
import com.medpilot.consult.ConsultationRecordRepository;
import com.medpilot.consult.ConsultationTraceRepository;
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

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MonitorTraceIntegrationTest {

    private static final String TRACE_ID = "2c293933-6590-4bfc-b0e8-507d3063c90b";
    private static final String SESSION_ID = "1779673a-c983-47e4-9715-f2d9548f469a";
    private static final String FAILED_TRACE_ID = "3c293933-6590-4bfc-b0e8-507d3063c90b";
    private static final String FAILED_SESSION_ID = "2779673a-c983-47e4-9715-f2d9548f469a";

    @Autowired MockMvc mvc;
    @Autowired ConsultationPersistenceService persistence;
    @Autowired ConsultationTraceRepository traces;
    @Autowired ConsultationRecordRepository records;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder passwordEncoder;
    @Autowired LiveTraceRegistry liveTraces;

    private Cookie adminCookie;
    private Cookie userCookie;
    private Cookie auditorCookie;

    @BeforeEach
    void setup() throws Exception {
        records.deleteAll();
        traces.deleteAll();
        adminCookie = login("admin", "admin123");
        userCookie = login("user", "user123");
        users.findByUsername("auditor").orElseGet(() -> users.save(
                new User("auditor", passwordEncoder.encode("auditor123"), Role.AUDITOR)));
        auditorCookie = login("auditor", "auditor123");

        var completed = new ConsultationEventAccumulator.Snapshot(
                TRACE_ID,
                SESSION_ID,
                "headache",
                null,
                null,
                null,
                null,
                null,
                null,
                "[]",
                "[{\"sequence\":1,\"type\":\"done\"}]",
                true,
                "completed",
                false,
                null,
                null,
                "[]",
                false,
                null,
                630L
        );
        persistence.persist(2L, "headache", "{}", completed);

        var failed = new ConsultationEventAccumulator.Snapshot(
                FAILED_TRACE_ID,
                FAILED_SESSION_ID,
                "cough",
                null,
                null,
                null,
                null,
                null,
                null,
                "[]",
                """
                [
                  {"sequence":1,"type":"node","node":"extract","status":"completed","elapsed_ms":120},
                  {"sequence":2,"type":"node","node":"retrieve","status":"error","elapsed_ms":250},
                  {"sequence":3,"type":"error","status":"error","elapsed_ms":0,"data":{"code":"inference_timeout"}}
                ]
                """,
                false,
                "failed",
                false,
                null,
                null,
                "[]",
                false,
                "inference_timeout",
                370L
        );
        persistence.persistFailure(2L, failed);
    }

    @Test
    void adminReadsPersistedTraceWithoutCallingAi() throws Exception {
        mvc.perform(get("/api/monitor/trace/" + TRACE_ID).cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.traceId").value(TRACE_ID))
                .andExpect(jsonPath("$.data.sessionId").value(SESSION_ID))
                .andExpect(jsonPath("$.data.events[0].sequence").value(1))
                .andExpect(jsonPath("$.data.followupPending").value(true));
    }

    @Test
    void auditorCanReadTraceButNormalUserCannotReadTraceList() throws Exception {
        mvc.perform(get("/api/monitor/trace/" + TRACE_ID).cookie(auditorCookie))
                .andExpect(status().isOk());

        mvc.perform(get("/api/monitor/traces").cookie(userCookie))
                .andExpect(status().isForbidden());
    }

    @Test
    void listsAndFiltersPersistedTraces() throws Exception {
        mvc.perform(get("/api/monitor/traces")
                        .param("terminalPhase", "failed")
                        .param("failureCode", "inference_timeout")
                        .param("sessionId", FAILED_SESSION_ID)
                        .cookie(auditorCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].traceId").value(FAILED_TRACE_ID))
                .andExpect(jsonPath("$.data[0].status").value("failed"))
                .andExpect(jsonPath("$.data[0].failureCode").value("inference_timeout"))
                .andExpect(jsonPath("$.data[0].totalDurationMs").value(370))
                .andExpect(jsonPath("$.meta.total").value(1));
    }

    @Test
    void reportsErrorTimeoutAndNodeDurationStatistics() throws Exception {
        mvc.perform(get("/api/monitor/stats").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalTraces").value(2))
                .andExpect(jsonPath("$.data.failedTraces").value(1))
                .andExpect(jsonPath("$.data.timeoutTraces").value(1))
                .andExpect(jsonPath("$.data.averageDurationMs").value(630.0))
                .andExpect(jsonPath("$.data.errorCodes.inference_timeout").value(1))
                .andExpect(jsonPath("$.data.nodes.extract.count").value(1))
                .andExpect(jsonPath("$.data.nodes.extract.averageDurationMs").value(120.0))
                .andExpect(jsonPath("$.data.nodes.retrieve.errorCount").value(1));
    }

    @Test
    void exposesActiveTraceSnapshotsThroughLiveAndTraceEndpoints() throws Exception {
        String liveTraceId = "4c293933-6590-4bfc-b0e8-507d3063c90b";
        LiveTraceRegistry.Handle handle = liveTraces.start(
                "3779673a-c983-47e4-9715-f2d9548f469a", 2L);
        liveTraces.publish(handle, liveTraceId,
                "{\"type\":\"node\",\"node\":\"extract\",\"status\":\"started\"}");

        mvc.perform(get("/api/monitor/live").cookie(auditorCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[*].traceId",
                        org.hamcrest.Matchers.hasItem(liveTraceId)))
                .andExpect(jsonPath("$.data[*].status",
                        org.hamcrest.Matchers.hasItem("active")));

        mvc.perform(get("/api/monitor/trace/" + liveTraceId).cookie(auditorCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.traceId").value(liveTraceId))
                .andExpect(jsonPath("$.data.status").value("active"))
                .andExpect(jsonPath("$.data.events[0].node").value("extract"));
    }

    @Test
    void reportsANullAverageDurationWhenNoCompletedTraceMatchesTheWindow() throws Exception {
        mvc.perform(get("/api/monitor/stats")
                        .param("startTime", java.time.Instant.now().plusSeconds(3600).toString())
                        .cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.completedTraces").value(0))
                .andExpect(jsonPath("$.data.averageDurationMs")
                        .value(org.hamcrest.Matchers.nullValue()));
    }

    @Test
    void missingTraceReturns404() throws Exception {
        mvc.perform(get("/api/monitor/trace/00000000-0000-4000-8000-000000000000")
                        .cookie(adminCookie))
                .andExpect(status().isNotFound());
    }

    @Test
    void oldPostTraceEndpointIsRemoved() throws Exception {
        mvc.perform(post("/api/monitor/trace")
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"headache\"}"))
                .andExpect(status().isNotFound());
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
