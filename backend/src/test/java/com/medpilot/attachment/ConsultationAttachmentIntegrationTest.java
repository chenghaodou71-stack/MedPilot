package com.medpilot.attachment;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.medpilot.auth.JwtAuthFilter;
import jakarta.servlet.http.Cookie;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConsultationAttachmentIntegrationTest {

    private static final Path STORAGE_ROOT = createStorageRoot();

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ConsultationAttachmentRepository attachments;

    private Cookie adminCookie;
    private Cookie userCookie;

    @DynamicPropertySource
    static void attachmentProperties(DynamicPropertyRegistry registry) {
        registry.add("medpilot.attachments.storage-dir", STORAGE_ROOT::toString);
    }

    @BeforeEach
    void setup() throws Exception {
        attachments.deleteAll();
        clearStorageFiles();
        adminCookie = login("admin", "admin123");
        userCookie = login("user", "user123");
    }

    @AfterAll
    static void cleanupStorage() throws IOException {
        if (!Files.exists(STORAGE_ROOT)) return;
        try (var paths = Files.walk(STORAGE_ROOT)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException exception) {
                    throw new IllegalStateException("failed to clean attachment test storage", exception);
                }
            });
        }
    }

    @Test
    void textUploadCreatesPrivateConfirmationDraftAndSanitizesFilename() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "../../symptoms.txt", "text/plain",
                "fever for three days and worsening cough".getBytes());

        String response = mvc.perform(multipart("/api/consult/attachments")
                        .file(file)
                        .param("session_id", UUID.randomUUID().toString())
                        .cookie(userCookie)
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.originalFilename").value("symptoms.txt"))
                .andExpect(jsonPath("$.data.kind").value("TEXT"))
                .andExpect(jsonPath("$.data.status").value("AWAITING_CONFIRMATION"))
                .andExpect(jsonPath("$.data.confirmationRequired").value(true))
                .andExpect(jsonPath("$.data.automaticAnalysisAllowed").value(false))
                .andExpect(jsonPath("$.data.draftText").value("fever for three days and worsening cough"))
                .andReturn().getResponse().getContentAsString();

        JsonNode payload = mapper.readTree(response).path("data");
        ConsultationAttachment stored = attachments.findById(payload.path("id").asText()).orElseThrow();
        Path storedFile = STORAGE_ROOT.resolve(stored.getStorageKey()).normalize();
        assertThat(storedFile).startsWith(STORAGE_ROOT);
        assertThat(stored.getStorageKey()).matches("[0-9a-f-]{36}\\.bin");
        assertThat(new String(Files.readAllBytes(storedFile), java.nio.charset.StandardCharsets.ISO_8859_1))
                .doesNotContain("worsening cough");
    }

    @Test
    void pdfTextIsExtractedButNeverAutomaticallyConfirmed() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "visit-note.pdf", "application/pdf", createPdf("persistent headache"));

        mvc.perform(multipart("/api/consult/attachments")
                        .file(file)
                        .param("session_id", UUID.randomUUID().toString())
                        .cookie(userCookie)
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("TEXT"))
                .andExpect(jsonPath("$.data.draftText").value(org.hamcrest.Matchers.containsString("persistent headache")))
                .andExpect(jsonPath("$.data.status").value("AWAITING_CONFIRMATION"));
    }

    @Test
    void imageUploadReturnsOnlyAUserEditableDraft() throws Exception {
        byte[] png = new byte[] {
                (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a,
                0x00, 0x00, 0x00, 0x00
        };
        MockMultipartFile file = new MockMultipartFile("file", "rash.png", "image/png", png);

        mvc.perform(multipart("/api/consult/attachments")
                        .file(file)
                        .param("session_id", UUID.randomUUID().toString())
                        .cookie(userCookie)
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.kind").value("IMAGE"))
                .andExpect(jsonPath("$.data.extractedText").value(""))
                .andExpect(jsonPath("$.data.draftText").value(org.hamcrest.Matchers.containsString("rash.png")))
                .andExpect(jsonPath("$.data.automaticAnalysisAllowed").value(false));
    }

    @Test
    void forgedContentTypeAndUnsupportedExtensionAreRejectedWithoutPersistence() throws Exception {
        MockMultipartFile forged = new MockMultipartFile(
                "file", "not-an-image.png", "image/png", "plain text".getBytes());
        mvc.perform(multipart("/api/consult/attachments")
                        .file(forged)
                        .param("session_id", UUID.randomUUID().toString())
                        .cookie(userCookie)
                        .with(csrf()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("File content does not match its declared type"));

        MockMultipartFile executable = new MockMultipartFile(
                "file", "payload.exe", "application/octet-stream", new byte[] {1, 2, 3});
        mvc.perform(multipart("/api/consult/attachments")
                        .file(executable)
                        .param("session_id", UUID.randomUUID().toString())
                        .cookie(userCookie)
                        .with(csrf()))
                .andExpect(status().isBadRequest());

        assertThat(attachments.count()).isZero();
        try (var storedFiles = Files.list(STORAGE_ROOT)) {
            assertThat(storedFiles).isEmpty();
        }
    }

    @Test
    void oversizedUploadIsRejected() throws Exception {
        byte[] oversized = new byte[ConsultationAttachmentStorage.MAX_FILE_BYTES + 1];
        oversized[0] = 'x';
        MockMultipartFile file = new MockMultipartFile("file", "large.txt", "text/plain", oversized);

        mvc.perform(multipart("/api/consult/attachments")
                        .file(file)
                        .param("session_id", UUID.randomUUID().toString())
                        .cookie(userCookie)
                        .with(csrf()))
                .andExpect(status().isPayloadTooLarge());

        assertThat(attachments.count()).isZero();
    }

    @Test
    void onlyOwnerCanConfirmAndDeleteAttachment() throws Exception {
        String attachmentId = uploadText(adminCookie, "owner.txt", "owner supplied note");

        mvc.perform(patch("/api/consult/attachments/{id}/confirm", attachmentId)
                        .cookie(userCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draftText\":\"confirmed note\"}"))
                .andExpect(status().isForbidden());

        mvc.perform(patch("/api/consult/attachments/{id}/confirm", attachmentId)
                        .cookie(adminCookie)
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"draftText\":\"confirmed note\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.status").value("CONFIRMED"))
                .andExpect(jsonPath("$.data.confirmedText").value("confirmed note"));

        ConsultationAttachment stored = attachments.findById(attachmentId).orElseThrow();
        Path storedPath = STORAGE_ROOT.resolve(stored.getStorageKey());
        assertThat(Files.exists(storedPath)).isTrue();

        mvc.perform(delete("/api/consult/attachments/{id}", attachmentId)
                        .cookie(adminCookie)
                        .with(csrf()))
                .andExpect(status().isNoContent());
        assertThat(attachments.findById(attachmentId)).isEmpty();
        assertThat(Files.exists(storedPath)).isFalse();
    }

    @Test
    void uploadRequiresAuthenticationAndCsrf() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "note.txt", "text/plain", "note".getBytes());

        mvc.perform(multipart("/api/consult/attachments")
                        .file(file)
                        .param("session_id", UUID.randomUUID().toString())
                        .with(csrf()))
                .andExpect(status().isUnauthorized());

        mvc.perform(multipart("/api/consult/attachments")
                        .file(file)
                        .param("session_id", UUID.randomUUID().toString())
                        .cookie(userCookie))
                .andExpect(status().isForbidden());
    }

    private String uploadText(Cookie cookie, String filename, String body) throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", filename, "text/plain", body.getBytes());
        String response = mvc.perform(multipart("/api/consult/attachments")
                        .file(file)
                        .param("session_id", UUID.randomUUID().toString())
                        .cookie(cookie)
                        .with(csrf()))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return mapper.readTree(response).path("data").path("id").asText();
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

    private static byte[] createPdf(String text) throws IOException {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream output = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(72, 720);
                content.showText(text);
                content.endText();
            }
            document.save(output);
            return output.toByteArray();
        }
    }

    private static Path createStorageRoot() {
        try {
            return Files.createTempDirectory("medpilot-attachment-tests-").toAbsolutePath().normalize();
        } catch (IOException exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    private static void clearStorageFiles() throws IOException {
        if (!Files.exists(STORAGE_ROOT)) return;
        try (var paths = Files.list(STORAGE_ROOT)) {
            for (Path path : paths.toList()) Files.deleteIfExists(path);
        }
    }
}
