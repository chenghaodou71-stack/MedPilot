package com.medpilot.auth;

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
import org.springframework.test.web.servlet.MvcResult;

import java.util.EnumMap;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class RoleAuthorizationIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;

    private final Map<Role, Cookie> cookies = new EnumMap<>(Role.class);

    @BeforeEach
    void setup() throws Exception {
        for (Role role : new Role[]{Role.KNOWLEDGE_EDITOR, Role.REVIEWER, Role.DOCTOR, Role.AUDITOR}) {
            String username = role.name().toLowerCase() + "-" + UUID.randomUUID();
            users.save(new User(username, encoder.encode("role-pass"), role));
            cookies.put(role, login(username, "role-pass"));
        }
    }

    @Test
    void auditorCanReadMonitorButCannotMutateKnowledge() throws Exception {
        mvc.perform(get("/api/monitor/trace/00000000-0000-4000-8000-000000000000")
                        .cookie(cookies.get(Role.AUDITOR)))
                .andExpect(status().isNotFound());

        mvc.perform(post("/api/knowledge/ingest")
                        .cookie(cookies.get(Role.AUDITOR))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void editorCannotApproveAndReviewerCannotIngest() throws Exception {
        mvc.perform(post("/api/knowledge/docs/demo/review")
                        .cookie(cookies.get(Role.KNOWLEDGE_EDITOR))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"approve\",\"reviewer\":\"editor\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/knowledge/ingest")
                        .cookie(cookies.get(Role.REVIEWER))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void doctorCanReachReviewBoundary() throws Exception {
        MvcResult result = mvc.perform(post("/api/knowledge/docs/demo/review")
                        .cookie(cookies.get(Role.DOCTOR))
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"approve\",\"reviewer\":\"doctor\"}"))
                .andReturn();
        if (result.getRequest().isAsyncStarted()) {
            result = mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch(result))
                    .andReturn();
        }
        assertThat(result.getResponse().getStatus()).isNotEqualTo(403);
    }

    private Cookie login(String username, String password) throws Exception {
        return mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getCookie(JwtAuthFilter.AUTH_COOKIE_NAME);
    }
}
