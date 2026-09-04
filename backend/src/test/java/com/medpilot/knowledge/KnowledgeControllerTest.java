package com.medpilot.knowledge;

import com.medpilot.common.ApiResponse;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.ExchangeStrategies;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.mock.web.MockMultipartFile;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import java.util.Optional;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import org.mockito.ArgumentCaptor;

class KnowledgeControllerTest {

    private final AtomicReference<ClientRequest> capturedRequest = new AtomicReference<>();

    @Test
    void listsVersionsAndCurrentActiveVersion() {
        KnowledgeController controller = controllerReturning(
                HttpStatus.OK,
                """
                {"current":"v2","versions":[{"version":"v2","active":true}]}
                """);

        ResponseEntity<ApiResponse<Object>> response = controller.versions().block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(capturedRequest.get().method()).isEqualTo(HttpMethod.GET);
        assertThat(capturedRequest.get().url().getPath()).isEqualTo("/knowledge/versions");
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isTrue();
        Map<?, ?> data = (Map<?, ?>) response.getBody().data();
        assertThat(data.get("current")).isEqualTo("v2");
    }

    @Test
    void buildsDraftVersion() {
        KnowledgeController controller = controllerReturning(
                HttpStatus.CREATED,
                """
                {"version":"v3","document_count":2,"chunk_count":8}
                """);

        ResponseEntity<ApiResponse<Object>> response = controller.buildVersion().block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(capturedRequest.get().method()).isEqualTo(HttpMethod.POST);
        assertThat(capturedRequest.get().url().getPath()).isEqualTo("/knowledge/versions/build");
    }

    @Test
    void activatesSelectedVersion() {
        KnowledgeController controller = controllerReturning(
                HttpStatus.OK,
                """
                {"active":"v3","manifest":{"version":"v3"}}
                """);

        ResponseEntity<ApiResponse<Object>> response = controller.activateVersion("v3").block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(capturedRequest.get().method()).isEqualTo(HttpMethod.POST);
        assertThat(capturedRequest.get().url().getPath())
                .isEqualTo("/knowledge/versions/v3/activate");
    }

    @Test
    void preservesUpstreamActivationErrorStatus() {
        KnowledgeController controller = controllerReturning(
                HttpStatus.NOT_FOUND,
                """
                {"detail":"version 'missing' not found"}
                """);

        ResponseEntity<ApiResponse<Object>> response = controller.activateVersion("missing").block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().success()).isFalse();
        assertThat(response.getBody().error()).contains("version 'missing' not found");
    }

    @Test
    void reportsBadGatewayWhenVersionServiceIsUnavailable() {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> Mono.error(new IllegalStateException("offline")));
        KnowledgeController controller = new KnowledgeController(
                builder.baseUrl("http://ai-service.test").build());

        ResponseEntity<ApiResponse<Object>> response = controller.versions().block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_GATEWAY);
        assertThat(response.getBody()).isNotNull();
        assertThat(response.getBody().error()).isEqualTo("ai-service unreachable");
        assertThat(response.getBody().error()).doesNotContain("offline");
    }

    @Test
    void ingestPayloadCannotSelfApproveOrForgeReviewMetadata() {
        Map<String, Object> sanitized = KnowledgeController.sanitizeIngestPayload(Map.of(
                "title", "demo",
                "review_status", "approved",
                "reviewer", "attacker",
                "reviewed_at", "2099-01-01T00:00:00Z",
                "checksum", "forged"));

        assertThat(sanitized.get("review_status")).isEqualTo("pending");
        assertThat(sanitized).doesNotContainKeys("reviewer", "reviewed_at");
        assertThat(sanitized.get("checksum")).isNotEqualTo("forged");
    }

    @Test
    void reviewUsesAuthenticatedPrincipalInsteadOfClientReviewer() {
        KnowledgeController controller = controllerReturning(HttpStatus.OK, "{}");

        controller.reviewDoc("demo", Map.of("action", "approve", "reviewer", "attacker"),
                () -> "trusted-reviewer").block();

        assertThat(capturedRequest.get().headers().getFirst("X-MedPilot-Reviewer"))
                .isEqualTo("trusted-reviewer");
    }

    @Test
    void uploadsMarkdownAsPendingAndPersistsLifecycleMetadata() {
        KnowledgeDocumentRepository repository = mock(KnowledgeDocumentRepository.class);
        when(repository.findById("upload-doc")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        KnowledgeController controller = controllerReturning(
                HttpStatus.CREATED, "{\"doc_id\":\"upload-doc\",\"review_status\":\"pending\"}", repository);
        MockMultipartFile file = new MockMultipartFile(
                "file", "guidance.md", "text/markdown",
                "咳嗽应记录持续时间。".getBytes(StandardCharsets.UTF_8));

        ResponseEntity<ApiResponse<Object>> response = controller.upload(
                file, "upload-doc", "呼吸内科", "公开指南",
                "测试机构", "呼吸科资料", "https://example.org/guidance",
                "2026-08-01", "v1", "CC BY 4.0", "", "").block();

        assertThat(response).isNotNull();
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(capturedRequest.get().url().getPath()).isEqualTo("/knowledge/ingest");
        ArgumentCaptor<KnowledgeDocument> captor = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        KnowledgeDocument stored = captor.getValue();
        assertThat(stored.getSourceType()).isEqualTo("md");
        assertThat(stored.getParsingStatus()).isEqualTo("completed");
        assertThat(stored.getReviewStatus()).isEqualTo("pending");
    }

    @Test
    void malformedTextUploadLeavesQueryableFailureMetadata() {
        KnowledgeDocumentRepository repository = mock(KnowledgeDocumentRepository.class);
        when(repository.findById("broken-doc")).thenReturn(Optional.empty());
        when(repository.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        KnowledgeController controller = controllerReturning(HttpStatus.CREATED, "{}", repository);
        MockMultipartFile file = new MockMultipartFile(
                "file", "broken.txt", "text/plain", new byte[]{(byte) 0xC3, (byte) 0x28});

        assertThatThrownBy(() -> controller.upload(
                file, "broken-doc", "呼吸内科", "公开资料",
                "测试机构", "损坏资料", "https://example.org/broken",
                "2026-08-01", "v1", "CC BY 4.0", "", ""))
                .isInstanceOf(IllegalArgumentException.class);

        ArgumentCaptor<KnowledgeDocument> captor = ArgumentCaptor.forClass(KnowledgeDocument.class);
        verify(repository, atLeastOnce()).save(captor.capture());
        assertThat(captor.getValue().getParsingStatus()).isEqualTo("failed");
        assertThat(captor.getValue().getVectorStatus()).isEqualTo("failed");
    }

    @Test
    void listDocsIncludesLocallyPersistedParsingFailuresMissingFromAiIndex() {
        KnowledgeDocumentRepository repository = mock(KnowledgeDocumentRepository.class);
        KnowledgeDocument failed = new KnowledgeDocument("broken-doc");
        failed.apply(Map.of(
                "title", "损坏资料",
                "department", "呼吸内科",
                "source_type", "txt"),
                new KnowledgeDocument.UploadMetadata("broken.txt", "text/plain", 2));
        failed.markParsingFailed("knowledge file could not be parsed",
                new KnowledgeDocument.UploadMetadata("broken.txt", "text/plain", 2));
        when(repository.findAll()).thenReturn(List.of(failed));
        KnowledgeController controller = controllerReturning(
                HttpStatus.OK, "{\"docs\":[],\"count\":0}", repository);

        ResponseEntity<ApiResponse<Object>> response = controller.listDocs().block();

        assertThat(response).isNotNull();
        Map<?, ?> data = (Map<?, ?>) response.getBody().data();
        List<?> docs = (List<?>) data.get("docs");
        assertThat(docs).hasSize(1);
        Map<?, ?> row = (Map<?, ?>) docs.get(0);
        assertThat(row.get("doc_id")).isEqualTo("broken-doc");
        assertThat(row.get("parsing_status")).isEqualTo("failed");
        assertThat(row.get("vector_status")).isEqualTo("failed");
        assertThat(row.get("processing_error")).isEqualTo("knowledge file could not be parsed");
        assertThat(data.get("count")).isEqualTo(1);
    }

    @Test
    void statsIncludeLocallyPersistedFailuresAndPreserveRetrievalMetrics() {
        KnowledgeDocumentRepository repository = mock(KnowledgeDocumentRepository.class);
        KnowledgeDocument failed = new KnowledgeDocument("broken-doc");
        failed.markParsingFailed("document parsing failed",
                new KnowledgeDocument.UploadMetadata("broken.pdf", "application/pdf", 10));
        when(repository.findAll()).thenReturn(List.of(failed));
        KnowledgeController controller = controllerReturning(
                HttpStatus.OK,
                "{\"docs\":[],\"retrieval_requests\":5,\"retrieval_hits\":2,\"hit_rate\":0.4}",
                repository);

        ResponseEntity<ApiResponse<Object>> response = controller.stats().block();

        assertThat(response).isNotNull();
        Map<?, ?> data = (Map<?, ?>) response.getBody().data();
        assertThat(data.get("total_docs")).isEqualTo(1);
        assertThat(data.get("retrieval_requests")).isEqualTo(5);
        assertThat(data.get("retrieval_hits")).isEqualTo(2);
        assertThat(data.get("hit_rate")).isEqualTo(0.4);
        assertThat(((Map<?, ?>) data.get("parsing_statuses")).get("failed")).isEqualTo(1L);
        assertThat(((Map<?, ?>) data.get("vector_statuses")).get("failed")).isEqualTo(1L);
    }

    private KnowledgeController controllerReturning(HttpStatus status, String body) {
        return controllerReturning(status, body, null);
    }

    private KnowledgeController controllerReturning(
            HttpStatus status,
            String body,
            KnowledgeDocumentRepository repository) {
        WebClient.Builder builder = WebClient.builder()
                .exchangeFunction(request -> {
                    capturedRequest.set(request);
                    ClientResponse response = ClientResponse
                            .create(status, ExchangeStrategies.withDefaults())
                            .header(HttpHeaders.CONTENT_TYPE, MediaType.APPLICATION_JSON_VALUE)
                            .body(body)
                            .build();
                    return Mono.just(response);
                });
        WebClient client = builder.baseUrl("http://ai-service.test").build();
        return repository == null
                ? new KnowledgeController(client)
                : new KnowledgeController(client, repository);
    }
}
