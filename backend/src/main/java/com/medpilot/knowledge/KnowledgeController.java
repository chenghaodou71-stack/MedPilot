package com.medpilot.knowledge;

import com.medpilot.common.ApiResponse;
import com.medpilot.config.AiServiceClientConfig;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * 知识库管理代理：鉴权由 SecurityConfig 统一处理（需 ROLE_ADMIN），
 * 实际向量化与索引操作转发到 ai-service /knowledge/*。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

    private final WebClient aiClient;

    public KnowledgeController(
            @Qualifier(AiServiceClientConfig.CLIENT_BEAN) WebClient aiClient) {
        this.aiClient = aiClient;
    }

    @GetMapping("/docs")
    public Mono<ResponseEntity<ApiResponse<Object>>> listDocs() {
        return proxy(aiClient.get().uri("/knowledge/docs").retrieve().bodyToMono(Object.class));
    }

    @GetMapping("/stats")
    public Mono<ResponseEntity<ApiResponse<Object>>> stats() {
        return proxy(aiClient.get().uri("/knowledge/stats").retrieve().bodyToMono(Object.class));
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
        return proxy(aiClient.post()
                .uri("/knowledge/ingest")
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Object.class));
    }

    @PostMapping("/docs/{docId}/review")
    public Mono<ResponseEntity<ApiResponse<Object>>> reviewDoc(
            @PathVariable String docId,
            @RequestBody Map<String, Object> body) {
        return proxy(aiClient.post()
                .uri("/knowledge/docs/{docId}/review", docId)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(Object.class));
    }

    @DeleteMapping("/{docId}")
    public Mono<ResponseEntity<ApiResponse<Object>>> deleteDoc(@PathVariable String docId) {
        return proxy(aiClient.delete()
                .uri("/knowledge/{docId}", docId)
                .retrieve()
                .bodyToMono(Object.class));
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
