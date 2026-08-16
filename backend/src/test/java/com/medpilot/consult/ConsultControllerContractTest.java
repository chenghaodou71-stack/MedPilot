package com.medpilot.consult;

import com.medpilot.auth.JwtAuthFilter;
import com.medpilot.config.RequestBodyLimitFilter;
import com.medpilot.user.UserRepository;
import com.medpilot.health.HealthProfileContextService;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Flux;

import java.nio.charset.StandardCharsets;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConsultControllerContractTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    SessionOwnershipService ownership;

    @Autowired
    UserRepository users;

    @MockBean
    AiConsultClient aiClient;

    @MockBean
    HealthProfileContextService healthProfiles;

    private Cookie userCookie;
    private Cookie adminCookie;

    @BeforeEach
    void setup() throws Exception {
        when(healthProfiles.resolveForUser(org.mockito.ArgumentMatchers.anyLong()))
                .thenReturn(java.util.Map.of());
        userCookie = login("user", "user123");
        adminCookie = login("admin", "admin123");
    }

    @Test
    void rejectsBlankTextBeforeCallingAi() throws Exception {
        mvc.perform(post("/api/consult")
                        .cookie(userCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"   \",\"session_id\":\"1779673a-c983-47e4-9715-f2d9548f469a\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(aiClient);
    }

    @Test
    void rejectsMissingOrInvalidSessionBeforeCallingAi() throws Exception {
        mvc.perform(post("/api/consult")
                        .cookie(userCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"咳嗽三天\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/consult")
                        .cookie(userCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"咳嗽三天\",\"session_id\":\"not-a-uuid\"}"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(aiClient);
    }

    @Test
    void rejectsMalformedJsonAsBadRequest() throws Exception {
        mvc.perform(post("/api/consult")
                        .cookie(userCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{invalid"))
                .andExpect(status().isBadRequest());

        verifyNoInteractions(aiClient);
    }

    @Test
    void rejectsSessionOwnedByAnotherUserBeforeCallingAi() throws Exception {
        Long userId = users.findByUsername("user").orElseThrow().getId();
        ownership.claim("1779673a-c983-47e4-9715-f2d9548f469a", userId);

        mvc.perform(post("/api/consult")
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"咳嗽三天\",\"session_id\":\"1779673a-c983-47e4-9715-f2d9548f469a\"}"))
                .andExpect(status().isForbidden());

        verifyNoInteractions(aiClient);
    }

    @Test
    void mapsUpstreamFailureBeforeFirstEventToBadGateway() throws Exception {
        when(aiClient.consult(anyString(), anyString(), anyMap()))
                .thenReturn(Flux.error(new IllegalStateException("offline")));

        MvcResult pending = mvc.perform(post("/api/consult")
                        .cookie(userCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"headache\",\"session_id\":\"3779673a-c983-47e4-9715-f2d9548f469a\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(pending))
                .andExpect(status().isBadGateway())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("AI service unavailable"));
    }

    @Test
    void preservesActionableUpstreamStatusesBeforeTheFirstEvent() throws Exception {
        assertUpstreamStatus(
                HttpStatus.CONFLICT,
                "4779673a-c983-47e4-9715-f2d9548f469a",
                "该问诊会话正在处理中");
        assertUpstreamStatus(
                HttpStatus.TOO_MANY_REQUESTS,
                "5779673a-c983-47e4-9715-f2d9548f469a",
                "AI 服务当前繁忙，请稍后重试");
        assertUpstreamStatus(
                HttpStatus.PAYLOAD_TOO_LARGE,
                "6779673a-c983-47e4-9715-f2d9548f469a",
                "请求内容超过 1 MiB 限制");
    }

    @Test
    void rejectsARequestBodyOverOneMiBBeforeCallingAi() throws Exception {
        String body = "{\"text\":\""
                + "x".repeat(RequestBodyLimitFilter.MAX_REQUEST_BYTES)
                + "\",\"session_id\":\"7779673a-c983-47e4-9715-f2d9548f469a\"}";

        mvc.perform(post("/api/consult")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body.getBytes(StandardCharsets.UTF_8)))
                .andExpect(status().isPayloadTooLarge())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("请求内容超过 1 MiB 限制"));

        verifyNoInteractions(aiClient);
    }

    private void assertUpstreamStatus(
            HttpStatus upstreamStatus,
            String sessionId,
            String expectedError) throws Exception {
        when(aiClient.consult(anyString(), anyString(), anyMap()))
                .thenReturn(Flux.error(WebClientResponseException.create(
                        upstreamStatus.value(),
                        upstreamStatus.getReasonPhrase(),
                        HttpHeaders.EMPTY,
                        new byte[0],
                        StandardCharsets.UTF_8)));

        MvcResult pending = mvc.perform(post("/api/consult")
                        .cookie(userCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"text\":\"headache\",\"session_id\":\""
                                + sessionId + "\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(pending))
                .andExpect(status().is(upstreamStatus.value()))
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value(expectedError));
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
