package com.medpilot.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class SecurityHardeningIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper mapper;
    @Autowired UserRepository users;
    @Autowired PasswordEncoder encoder;
    @Autowired JwtService jwtService;

    private User testUser;

    @BeforeEach
    void setUp() {
        String username = "security-" + UUID.randomUUID();
        testUser = users.save(new User(username, encoder.encode("valid-pass-123"), Role.USER));
    }

    @Test
    void csrfEndpointIssuesReadableXsrfCookie() throws Exception {
        mvc.perform(get("/api/auth/csrf"))
                .andExpect(status().isOk())
                .andExpect(cookie().exists("XSRF-TOKEN"))
                .andExpect(cookie().httpOnly("XSRF-TOKEN", false))
                .andExpect(jsonPath("$.data.token").isNotEmpty());
    }

    @Test
    void loginRequiresCsrf() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginBody(
                                testUser.getUsername(), "valid-pass-123"))))
                .andExpect(status().isForbidden());
    }

    @Test
    void loginSetsHttpOnlyJwtCookieAndLogoutClearsIt() throws Exception {
        var login = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginBody(
                                testUser.getUsername(), "valid-pass-123"))))
                .andExpect(status().isOk())
                .andExpect(cookie().httpOnly("MEDPILOT_AUTH", true))
                .andExpect(jsonPath("$.data.token").doesNotExist())
                .andReturn();

        Cookie authCookie = login.getResponse().getCookie("MEDPILOT_AUTH");
        assertThat(authCookie).isNotNull();

        mvc.perform(post("/api/auth/logout")
                        .with(csrf())
                        .cookie(authCookie))
                .andExpect(status().isOk())
                .andExpect(cookie().maxAge("MEDPILOT_AUTH", 0));

        mvc.perform(get("/api/auth/me").cookie(authCookie))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void restoresTheCurrentUserFromTheHttpOnlyCookie() throws Exception {
        var login = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginBody(
                                testUser.getUsername(), "valid-pass-123"))))
                .andExpect(status().isOk())
                .andReturn();

        Cookie authCookie = login.getResponse().getCookie("MEDPILOT_AUTH");
        mvc.perform(get("/api/auth/me").cookie(authCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value(testUser.getUsername()))
                .andExpect(jsonPath("$.data.role").value("USER"))
                .andExpect(jsonPath("$.data.token").doesNotExist());
    }

    @Test
    void cookieAuthenticatedMutationRequiresCsrfAndBearerHeadersAreIgnored() throws Exception {
        String token = jwtService.generate(testUser.getUsername(), testUser.getRole());
        Cookie authCookie = new Cookie("MEDPILOT_AUTH", token);

        mvc.perform(post("/api/auth/logout").cookie(authCookie))
                .andExpect(status().isForbidden());

        mvc.perform(post("/api/auth/logout")
                        .with(csrf())
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void loginRejectsNullAndOversizedFieldsAsBadRequest() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginBody("x".repeat(65), "password"))))
                .andExpect(status().isBadRequest());
    }

    @Test
    void repeatedLoginFailuresAreRateLimited() throws Exception {
        String unknown = "unknown-" + UUID.randomUUID();
        for (int i = 0; i < 3; i++) {
            mvc.perform(post("/api/auth/login")
                            .with(csrf())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(mapper.writeValueAsString(new LoginBody(unknown, "wrong-pass"))))
                    .andExpect(status().isUnauthorized());
        }

        mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(mapper.writeValueAsString(new LoginBody(unknown, "wrong-pass"))))
                .andExpect(status().isTooManyRequests());
    }

    @Test
    void disabledUserAndChangedRoleInvalidateExistingTokens() throws Exception {
        String userToken = jwtService.generate(testUser.getUsername(), Role.USER);
        testUser.setActive(false);
        users.saveAndFlush(testUser);

        mvc.perform(get("/api/me").cookie(new Cookie("MEDPILOT_AUTH", userToken)))
                .andExpect(status().isUnauthorized());

        testUser.setActive(true);
        testUser.setRole(Role.ADMIN);
        users.saveAndFlush(testUser);

        mvc.perform(get("/api/me").cookie(new Cookie("MEDPILOT_AUTH", userToken)))
                .andExpect(status().isUnauthorized());
    }

    private record LoginBody(String username, String password) {}
}
