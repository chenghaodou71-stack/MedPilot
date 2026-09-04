package com.medpilot.knowledge;

import com.medpilot.common.ApiResponse;
import com.medpilot.config.AiServiceClientConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.Map;
import java.util.LinkedHashMap;
import java.util.ArrayList;
import java.util.List;
import java.security.Principal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Objects;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

/**
 * 知识库管理代理：鉴权由 SecurityConfig 统一处理（需 ROLE_ADMIN），
 * 实际向量化与索引操作转发到 ai-service /knowledge/*。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    static final String REVIEWER_HEADER = "X-MedPilot-Reviewer";

    private final WebClient aiClient;
    private final KnowledgeDocumentRepository documents;

    @Autowired
    public KnowledgeController(
            @Qualifier(AiServiceClientConfig.CLIENT_BEAN) WebClient aiClient,
            KnowledgeDocumentRepository documents) {
        this.aiClient = aiClient;
        this.documents = documents;
    }

    public KnowledgeController(WebClient aiClient) {
        this.aiClient = aiClient;
        this.documents = null;
    }

    @GetMapping("/docs")
    public Mono<ResponseEntity<ApiResponse<Object>>> listDocs() {
        return proxy(aiClient.get().uri("/knowledge/docs").retrieve().bodyToMono(Object.class)
                .map(result -> {
                    synchronizeRemoteDocs(result);
                    return mergeDocumentMetadata(result);
                }));
    }

    @GetMapping("/stats")
    public Mono<ResponseEntity<ApiResponse<Object>>> stats() {
        Mono<Object> remoteStats = aiClient.get().uri("/knowledge/stats")
                .retrieve().bodyToMono(Object.class);
        Mono<Object> remoteDocs = aiClient.get().uri("/knowledge/docs")
                .retrieve().bodyToMono(Object.class);
        return proxy(Mono.zip(remoteStats, remoteDocs).map(result -> {
            synchronizeRemoteDocs(result.getT2());
            return mergeKnowledgeStats(result.getT1());
        }));
    }

    @GetMapping("/versions")
    public Mono<ResponseEntity<ApiResponse<Object>>> versions() {
        return proxy(aiClient.get().uri("/knowledge/versions").retrieve().bodyToMono(Object.class));
    }

    @PostMapping("/versions/build")
    public Mono<ResponseEntity<ApiResponse<Object>>> buildVersion() {
        return proxy(aiClient.post().uri("/knowledge/versions/build").retrieve().bodyToMono(Object.class));
    }

    @PostMapping("/versions/{version}/activate")
    public Mono<ResponseEntity<ApiResponse<Object>>> activateVersion(@PathVariable String version) {
        return proxy(aiClient.post()
                .uri("/knowledge/versions/{version}/activate", version)
                .retrieve()
                .bodyToMono(Object.class));
    }

    @GetMapping("/versions/{version}/diff")
    public Mono<ResponseEntity<ApiResponse<Object>>> diffVersion(
            @PathVariable String version,
            @RequestParam String against) {
        return proxy(aiClient.get()
                .uri(uriBuilder -> uriBuilder
                        .path("/knowledge/versions/{version}/diff")
                        .queryParam("against", against)
                        .build(version))
                .retrieve()
                .bodyToMono(Object.class));
    }

    @PostMapping("/ingest")
    public Mono<ResponseEntity<ApiResponse<Object>>> ingest(@RequestBody Map<String, Object> body) {
        Map<String, Object> sanitized = sanitizeIngestPayload(body);
        return proxy(aiClient.post()
                .uri("/knowledge/ingest")
                .bodyValue(sanitized)
                .retrieve()
                .bodyToMono(Object.class)
                .doOnNext(result -> persistDocument(sanitized, result, null)));
    }

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public Mono<ResponseEntity<ApiResponse<Object>>> upload(
            @RequestPart("file") MultipartFile file,
            @RequestParam("doc_id") String docId,
            @RequestParam String department,
            @RequestParam(defaultValue = "") String source,
            @RequestParam String institution,
            @RequestParam String title,
            @RequestParam String url,
            @RequestParam("published_date") String publishedDate,
            @RequestParam String version,
            @RequestParam String license,
            @RequestParam(defaultValue = "") String expiresAt,
            @RequestParam(defaultValue = "") String changeReason) {
        String canonicalId = requireDocumentId(docId);
        KnowledgeDocument.UploadMetadata upload = new KnowledgeDocument.UploadMetadata(
                safeFilename(file.getOriginalFilename()),
                Objects.toString(file.getContentType(), "application/octet-stream"),
                file.getSize());
        String sourceType = supportedExtension(upload.originalFilename());
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("doc_id", canonicalId);
        payload.put("department", department);
        payload.put("source", source == null || source.isBlank()
                ? institution.trim() + "｜" + title.trim()
                : source.trim());
        payload.put("source_type", sourceType);
        payload.put("institution", institution);
        payload.put("title", title);
        payload.put("url", url);
        payload.put("published_date", publishedDate);
        payload.put("version", version);
        payload.put("license", license);
        payload.put("review_status", "pending");
        payload.put("expires_at", expiresAt == null ? "" : expiresAt.trim());
        payload.put("change_reason", changeReason == null ? "" : changeReason.trim());

        KnowledgeDocument draft = findOrCreate(canonicalId);
        draft.apply(payload, upload);
        if (documents != null) documents.save(draft);
        try {
            payload.put("text", extractText(file));
        } catch (RuntimeException exception) {
            draft.markParsingFailed(exception.getMessage(), upload);
            if (documents != null) documents.save(draft);
            throw exception;
        }
        Map<String, Object> sanitized = sanitizeIngestPayload(payload);
        return proxy(aiClient.post()
                .uri("/knowledge/ingest")
                .bodyValue(sanitized)
                .retrieve()
                .bodyToMono(Object.class)
                .doOnNext(result -> persistDocument(sanitized, result, upload)));
    }

    @PostMapping("/docs/{docId}/review")
    public Mono<ResponseEntity<ApiResponse<Object>>> reviewDoc(
            @PathVariable String docId,
            @RequestBody Map<String, Object> body,
            Principal principal) {
        return proxy(aiClient.post()
                .uri("/knowledge/docs/{docId}/review", docId)
                .header(REVIEWER_HEADER, principal.getName())
                .bodyValue(sanitizeReviewPayload(body))
                .retrieve()
                .bodyToMono(Object.class)
                .doOnNext(result -> persistReview(docId, body, principal.getName(), result)));
    }

    static Map<String, Object> sanitizeIngestPayload(Map<String, ?> body) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (body != null) sanitized.putAll(body);
        sanitized.remove("reviewer");
        sanitized.remove("reviewed_at");
        sanitized.remove("checksum");
        sanitized.put("review_status", "pending");
        Object text = sanitized.get("text");
        if (text != null) sanitized.put("checksum", sha256(text.toString()));
        return sanitized;
    }

    static Map<String, Object> sanitizeReviewPayload(Map<String, ?> body) {
        Map<String, Object> sanitized = new LinkedHashMap<>();
        if (body != null) sanitized.putAll(body);
        sanitized.remove("reviewer");
        sanitized.remove("reviewed_at");
        sanitized.remove("checksum");
        return sanitized;
    }

    @DeleteMapping("/{docId}")
    public Mono<ResponseEntity<ApiResponse<Object>>> deleteDoc(@PathVariable String docId) {
        return proxy(aiClient.delete()
                .uri("/knowledge/{docId}", docId)
                .retrieve()
                .bodyToMono(Object.class)
                .doOnNext(ignored -> {
                    if (documents != null) documents.deleteById(docId);
                }));
    }

    private KnowledgeDocument findOrCreate(String docId) {
        if (documents == null) return new KnowledgeDocument(docId);
        return documents.findById(docId).orElseGet(() -> new KnowledgeDocument(docId));
    }

    private void persistDocument(
            Map<String, ?> request,
            Object response,
            KnowledgeDocument.UploadMetadata upload) {
        if (documents == null) return;
        Map<String, Object> merged = new LinkedHashMap<>();
        merged.putAll(request);
        if (response instanceof Map<?, ?> map) {
            map.forEach((key, value) -> merged.put(String.valueOf(key), value));
        }
        String docId = Objects.toString(merged.get("doc_id"), "").trim();
        if (docId.isBlank()) return;
        KnowledgeDocument document = findOrCreate(docId);
        document.apply(merged, upload);
        documents.save(document);
    }

    private void persistReview(String docId, Map<String, ?> request, String reviewer, Object response) {
        if (documents == null) return;
        KnowledgeDocument document = findOrCreate(docId);
        Map<String, Object> values = new LinkedHashMap<>();
        if (response instanceof Map<?, ?> map) {
            map.forEach((key, value) -> values.put(String.valueOf(key), value));
        }
        document.applyReview(Objects.toString(request.get("action"), ""), reviewer, values);
        documents.save(document);
    }

    private void synchronizeRemoteDocs(Object response) {
        if (documents == null || !(response instanceof Map<?, ?> result)) return;
        Object rows = result.get("docs");
        if (!(rows instanceof Iterable<?> iterable)) return;
        for (Object row : iterable) {
            if (!(row instanceof Map<?, ?> map)) continue;
            Map<String, Object> values = new LinkedHashMap<>();
            map.forEach((key, value) -> values.put(String.valueOf(key), value));
            persistDocument(values, values, null);
        }
    }

    private Object mergeDocumentMetadata(Object response) {
        if (documents == null) return response;
        Map<String, Object> result = new LinkedHashMap<>();
        if (response instanceof Map<?, ?> map) {
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
        }

        Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
        Object rows = result.get("docs");
        if (rows instanceof Iterable<?> iterable) {
            for (Object row : iterable) {
                if (!(row instanceof Map<?, ?> map)) continue;
                Map<String, Object> values = new LinkedHashMap<>();
                map.forEach((key, value) -> values.put(String.valueOf(key), value));
                String docId = Objects.toString(values.get("doc_id"), "").trim();
                if (!docId.isBlank()) byId.put(docId, values);
            }
        }

        for (KnowledgeDocument document : documents.findAll()) {
            Map<String, Object> merged = new LinkedHashMap<>();
            Map<String, Object> remote = byId.get(document.getDocId());
            if (remote != null) merged.putAll(remote);
            merged.putAll(document.view());
            byId.put(document.getDocId(), merged);
        }

        List<Map<String, Object>> mergedRows = new ArrayList<>(byId.values());
        result.put("docs", mergedRows);
        result.put("total", mergedRows.size());
        if (result.containsKey("count")) result.put("count", mergedRows.size());
        return result;
    }

    private Object mergeKnowledgeStats(Object response) {
        if (documents == null) return response;
        Map<String, Object> result = new LinkedHashMap<>();
        if (response instanceof Map<?, ?> map) {
            map.forEach((key, value) -> result.put(String.valueOf(key), value));
        }
        List<KnowledgeDocument> local = documents.findAll();
        Map<String, Long> parsingStatuses = new LinkedHashMap<>();
        Map<String, Long> vectorStatuses = new LinkedHashMap<>();
        local.forEach(document -> {
            parsingStatuses.merge(document.getParsingStatus(), 1L, Long::sum);
            vectorStatuses.merge(document.getVectorStatus(), 1L, Long::sum);
        });
        result.put("total_docs", local.size());
        result.put("active_docs", local.stream()
                .filter(document -> "approved".equals(document.getReviewStatus())
                        && "completed".equals(document.getVectorStatus()))
                .count());
        result.put("pending_docs", local.stream()
                .filter(document -> "pending".equals(document.getReviewStatus())).count());
        result.put("rejected_docs", local.stream()
                .filter(document -> "rejected".equals(document.getReviewStatus())).count());
        result.put("total_chunks", local.stream()
                .mapToInt(KnowledgeDocument::getChunkCount).sum());
        result.put("parsing_statuses", parsingStatuses);
        result.put("vector_statuses", vectorStatuses);
        return result;
    }

    private static String extractText(MultipartFile file) {
        if (file == null || file.isEmpty()) throw new IllegalArgumentException("knowledge file is empty");
        if (file.getSize() > 10L * 1024 * 1024) {
            throw new IllegalArgumentException("knowledge file must not exceed 10 MB");
        }
        String filename = safeFilename(file.getOriginalFilename());
        String extension = filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        try {
            byte[] bytes = file.getBytes();
            String text = switch (extension) {
                case "txt", "md" -> decodeUtf8(bytes);
                case "pdf" -> extractPdf(bytes);
                default -> throw new IllegalArgumentException("only TXT, MD and PDF files are supported");
            };
            String normalized = text.replace("\u0000", "").trim();
            if (normalized.isBlank()) throw new IllegalArgumentException("knowledge file contains no readable text");
            if (normalized.length() > 200_000) {
                throw new IllegalArgumentException("knowledge text must not exceed 200000 characters");
            }
            return normalized;
        } catch (IllegalArgumentException exception) {
            throw exception;
        } catch (Exception exception) {
            throw new IllegalArgumentException("knowledge file could not be parsed", exception);
        }
    }

    private static String decodeUtf8(byte[] bytes) throws CharacterCodingException {
        return StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT)
                .decode(ByteBuffer.wrap(bytes)).toString();
    }

    private static String extractPdf(byte[] bytes) throws Exception {
        try (PDDocument document = Loader.loadPDF(bytes)) {
            return new PDFTextStripper().getText(document);
        }
    }

    private static String safeFilename(String value) {
        if (value == null || value.isBlank()) return "document";
        return value.replace('\\', '/').substring(value.replace('\\', '/').lastIndexOf('/') + 1);
    }

    private static String supportedExtension(String filename) {
        String extension = filename.contains(".")
                ? filename.substring(filename.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT) : "";
        if (!java.util.Set.of("txt", "md", "pdf").contains(extension)) {
            throw new IllegalArgumentException("only TXT, MD and PDF files are supported");
        }
        return extension;
    }

    private static String requireDocumentId(String value) {
        String docId = value == null ? "" : value.trim();
        if (!docId.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,127}")) {
            throw new IllegalArgumentException("document id format is invalid");
        }
        return docId;
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private Mono<ResponseEntity<ApiResponse<Object>>> proxy(Mono<Object> upstream) {
        return upstream
                .map(body -> ResponseEntity.ok(ApiResponse.ok(body)))
                .onErrorResume(WebClientResponseException.class, ex ->
                        Mono.just(ResponseEntity
                                .status(ex.getStatusCode())
                                .body(ApiResponse.fail(ex.getResponseBodyAsString()))))
                .onErrorResume(e ->
                        Mono.just(ResponseEntity
                                .status(HttpStatus.BAD_GATEWAY)
                                .body(ApiResponse.fail("ai-service unreachable"))));
    }
}
