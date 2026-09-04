package com.medpilot.auth;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.Cookie;
import com.medpilot.user.Role;
import com.medpilot.user.User;
import com.medpilot.user.UserRepository;
import com.medpilot.consult.ConsultationRecord;
import com.medpilot.consult.ConsultationRecordRepository;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuthIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    UserRepository users;

    @Autowired
    PasswordEncoder passwordEncoder;

    @Autowired
    ConsultationRecordRepository records;

    private Cookie login(String username, String password) throws Exception {
        MvcResult res = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andReturn();
        return res.getResponse().getCookie(JwtAuthFilter.AUTH_COOKIE_NAME);
    }

    @Test
    void loginSucceedsWithValidCredentials() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("ADMIN"))
                .andExpect(jsonPath("$.data.token").doesNotExist());
    }

    @Test
    void loginFailsWithBadPassword() throws Exception {
        mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"wrong\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.success").value(false))
                .andExpect(jsonPath("$.error").value("用户名或密码错误"));
    }

    @Test
    void adminEndpointAllowsAdmin() throws Exception {
        Cookie cookie = login("admin", "admin123");
        mvc.perform(get("/api/admin/users").cookie(cookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));
    }

    @Test
    void adminEndpointForbidsNormalUser() throws Exception {
        Cookie cookie = login("user", "user123");
        mvc.perform(get("/api/admin/users").cookie(cookie))
                .andExpect(status().isForbidden());
    }

    @Test
    void protectedEndpointRejectsAnonymous() throws Exception {
        mvc.perform(get("/api/me"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCanChangeRoleAndDisableAUser() throws Exception {
        String username = "managed-" + UUID.randomUUID();
        User managed = users.save(new User(username, passwordEncoder.encode("managed-pass"), Role.USER));
        Cookie admin = login("admin", "admin123");

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                        "/api/admin/users/" + managed.getId())
                        .cookie(admin)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"AUDITOR\",\"active\":false}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.role").value("AUDITOR"))
                .andExpect(jsonPath("$.data.active").value(false));

        mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"managed-pass\"}"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void adminCannotDisableThemselves() throws Exception {
        Cookie admin = login("admin", "admin123");
        Long adminId = users.findByUsername("admin").orElseThrow().getId();

        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch(
                        "/api/admin/users/" + adminId)
                        .cookie(admin)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"active\":false}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void adminCanCreateAndDeleteAnUnreferencedUser() throws Exception {
        Cookie admin = login("admin", "admin123");
        String username = "created-" + UUID.randomUUID();

        MvcResult created = mvc.perform(post("/api/admin/users")
                        .cookie(admin)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username
                                + "\",\"password\":\"strong-pass-123\",\"role\":\"USER\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.username").value(username))
                .andReturn();

        long id = mapper.readTree(created.getResponse().getContentAsString())
                .path("data").path("id").asLong();
        mvc.perform(delete("/api/admin/users/" + id).cookie(admin).with(csrf()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.deleted").value(true));
    }

    @Test
    void deleteRejectsSelfAndUsersWithConsultationRecords() throws Exception {
        Cookie admin = login("admin", "admin123");
        User currentAdmin = users.findByUsername("admin").orElseThrow();

        mvc.perform(delete("/api/admin/users/" + currentAdmin.getId())
                        .cookie(admin).with(csrf()))
                .andExpect(status().isForbidden());

        User referenced = users.save(new User(
                "referenced-" + UUID.randomUUID(), passwordEncoder.encode("referenced-pass"), Role.USER));
        records.save(new ConsultationRecord(referenced.getId(), UUID.randomUUID().toString()));
        mvc.perform(delete("/api/admin/users/" + referenced.getId())
                        .cookie(admin).with(csrf()))
                .andExpect(status().isConflict());
    }
}
