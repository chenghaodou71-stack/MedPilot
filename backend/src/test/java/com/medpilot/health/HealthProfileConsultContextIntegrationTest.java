package com.medpilot.health;

import com.medpilot.auth.JwtAuthFilter;
import com.medpilot.consult.AiConsultClient;
import com.medpilot.user.UserRepository;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import reactor.core.publisher.Flux;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class HealthProfileConsultContextIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    HealthProfileRepository profiles;

    @Autowired
    UserRepository users;

    @MockBean
    AiConsultClient aiClient;

    private Cookie userCookie;

    @BeforeEach
    void setup() throws Exception {
        profiles.deleteAll();
        userCookie = login("user", "user123");
        when(aiClient.consult(anyString(), anyString(), anyMap())).thenAnswer(invocation ->
                Flux.just(done(invocation.getArgument(1))));
    }

    @Test
    void injectsTheAuthenticatedUsersConsentedProfile() throws Exception {
        updateProfile(true, "青霉素", "哮喘");

        submitConsult(UUID.randomUUID().toString());

        ArgumentCaptor<Map<String, String>> context = ArgumentCaptor.forClass(Map.class);
        verify(aiClient).consult(
                org.mockito.ArgumentMatchers.eq("当前咳嗽"),
                anyString(),
                context.capture());
        assertThat(context.getValue()).containsExactlyInAnyOrderEntriesOf(Map.of(
                "allergies", "青霉素",
                "conditions", "哮喘"));
    }

    @Test
    void omitsContextAfterConsentIsRevoked() throws Exception {
        updateProfile(true, "青霉素", "哮喘");
        updateProfile(false, "青霉素", "哮喘");

        submitConsult(UUID.randomUUID().toString());

        ArgumentCaptor<Map<String, String>> context = ArgumentCaptor.forClass(Map.class);
        verify(aiClient).consult(anyString(), anyString(), context.capture());
        assertThat(context.getValue()).isEmpty();
    }

    @Test
    void neverFallsBackToAnotherUsersProfile() throws Exception {
        Long adminId = users.findByUsername("admin").orElseThrow().getId();
        profiles.save(new HealthProfile(
                adminId,
                "{\"allergies\":\"private-admin-allergy\"}",
                true));

        submitConsult(UUID.randomUUID().toString());

        ArgumentCaptor<Map<String, String>> context = ArgumentCaptor.forClass(Map.class);
        verify(aiClient).consult(anyString(), anyString(), context.capture());
        assertThat(context.getValue()).isEmpty();
    }

    private void updateProfile(boolean consent, String allergies, String conditions)
            throws Exception {
        mvc.perform(put("/api/profile")
                        .cookie(userCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"allergies":"%s","conditions":"%s","consentGranted":%s}
                                """.formatted(allergies, conditions, consent)))
                .andExpect(status().isOk());
    }

    private void submitConsult(String sessionId) throws Exception {
        MvcResult pending = mvc.perform(post("/api/consult")
                        .cookie(userCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"text":"当前咳嗽","session_id":"%s"}
                                """.formatted(sessionId)))
                .andExpect(request().asyncStarted())
                .andReturn();
        mvc.perform(asyncDispatch(pending)).andExpect(status().isOk());
    }

    private Cookie login(String username, String password) throws Exception {
        var response = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username
                                + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return response.getResponse().getCookie(JwtAuthFilter.AUTH_COOKIE_NAME);
    }

    private String done(String sessionId) {
        return "{\"protocol_version\":\"1.0\","
                + "\"trace_id\":\"" + UUID.randomUUID() + "\","
                + "\"session_id\":\"" + sessionId + "\","
                + "\"sequence\":1,\"type\":\"done\",\"status\":\"completed\","
                + "\"elapsed_ms\":0,\"state\":{\"intent\":\"medical_consult\","
                + "\"phase\":\"completed\",\"turn_count\":1,"
                + "\"history_mode\":\"full\"},\"data\":{}}";
    }
}
