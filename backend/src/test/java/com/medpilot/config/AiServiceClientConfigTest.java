package com.medpilot.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medpilot.consult.WebClientAiConsultClient;
import com.medpilot.health.HealthController;
import com.medpilot.knowledge.KnowledgeController;
import com.medpilot.monitor.MonitorController;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.reactive.function.client.ClientRequest;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;

import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

class AiServiceClientConfigTest {

    @Test
    void rejectsMissingServiceToken() {
        AiServiceClientConfig config = new AiServiceClientConfig();

        assertThatThrownBy(() -> config.aiServiceWebClient(
                WebClient.builder(), "http://ai-service.test", " "))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void everyAiRouteUsesTheConfiguredServiceToken() {
        List<ClientRequest> requests = new ArrayList<>();
        WebClient.Builder builder = WebClient.builder().exchangeFunction(request -> {
            requests.add(request);
            String body = request.url().getPath().equals("/consult") ? "event\n" : "{}";
            return Mono.just(ClientResponse.create(HttpStatus.OK).body(body).build());
        });
        WebClient client = new AiServiceClientConfig().aiServiceWebClient(
                builder, "http://ai-service.test", "test-service-token");

        new WebClientAiConsultClient(client).consult("text", "session", java.util.Map.of())
                .collectList().block();
        new KnowledgeController(client).versions().block();
        new MonitorController(client, mock(com.medpilot.consult.ConsultationTraceRepository.class),
                new ObjectMapper()).health().block();
        new HealthController(client).aiHealth().block();

        assertThat(requests).hasSize(4);
        assertThat(requests)
                .allSatisfy(request -> assertThat(request.headers()
                        .getFirst(AiServiceClientConfig.SERVICE_TOKEN_HEADER))
                        .isEqualTo("test-service-token"));
    }

    @Test
    void realFastApiContractCoversUnauthorizedAndSuccessfulRequests() throws Exception {
        Path aiServiceDir = findAiServiceDirectory();
        int port = freeLoopbackPort();
        String baseUrl = "http://127.0.0.1:" + port;
        Path processLog = Files.createTempFile("medpilot-fastapi-contract-", ".log");
        processLog.toFile().deleteOnExit();

        ProcessBuilder processBuilder = new ProcessBuilder(
                findPythonExecutable(aiServiceDir),
                "-m", "uvicorn", "main:app",
                "--host", "127.0.0.1",
                "--port", Integer.toString(port),
                "--log-level", "warning");
        processBuilder.directory(aiServiceDir.toFile());
        processBuilder.redirectErrorStream(true);
        processBuilder.redirectOutput(processLog.toFile());
        processBuilder.environment().put("MEDPILOT_AI_SERVICE_TOKEN", "accepted-token");
        processBuilder.environment().put("MEDPILOT_CORS_ORIGINS", "");

        Process fastApi = processBuilder.start();

        try {
            awaitFastApi(fastApi, URI.create(baseUrl + "/"), processLog);
            ContractResponse accepted = getKnowledgeVersions(baseUrl, "accepted-token");
            ContractResponse rejected = getKnowledgeVersions(baseUrl, "rejected-token");

            assertThat(accepted.status()).isEqualTo(HttpURLConnection.HTTP_OK);
            assertThat(accepted.body()).contains("\"versions\"");
            assertThat(rejected.status()).isEqualTo(HttpURLConnection.HTTP_UNAUTHORIZED);
        } finally {
            fastApi.destroy();
            if (!fastApi.waitFor(3, TimeUnit.SECONDS)) {
                fastApi.destroyForcibly();
                fastApi.waitFor(3, TimeUnit.SECONDS);
            }
        }
    }

    private static ContractResponse getKnowledgeVersions(String baseUrl, String token)
            throws Exception {
        HttpURLConnection connection = (HttpURLConnection) URI.create(
                baseUrl + "/knowledge/versions").toURL().openConnection();
        connection.setConnectTimeout(2_000);
        connection.setReadTimeout(2_000);
        connection.setRequestMethod("GET");
        connection.setRequestProperty(AiServiceClientConfig.SERVICE_TOKEN_HEADER, token);
        int status = connection.getResponseCode();
        InputStream stream = status >= 400 ? connection.getErrorStream() : connection.getInputStream();
        String body = stream == null ? "" : new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        connection.disconnect();
        return new ContractResponse(status, body);
    }

    private record ContractResponse(int status, String body) { }

    private static Path findAiServiceDirectory() {
        for (Path candidate : List.of(Path.of("..", "ai-service"), Path.of("ai-service"))) {
            Path directory = candidate.toAbsolutePath().normalize();
            if (Files.isRegularFile(directory.resolve("main.py"))) {
                return directory;
            }
        }
        throw new IllegalStateException("ai-service directory not found from "
                + Path.of("").toAbsolutePath());
    }

    private static String findPythonExecutable(Path aiServiceDir) {
        String configured = System.getenv("MEDPILOT_PYTHON");
        if (configured != null && !configured.isBlank()) {
            return configured;
        }
        for (Path candidate : List.of(
                aiServiceDir.resolve("venv/Scripts/python.exe"),
                aiServiceDir.resolve("venv/bin/python"))) {
            if (Files.isRegularFile(candidate)) {
                return candidate.toString();
            }
        }
        return System.getProperty("os.name").toLowerCase().contains("win")
                ? "python"
                : "python3";
    }

    private static int freeLoopbackPort() throws Exception {
        try (ServerSocket socket = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            return socket.getLocalPort();
        }
    }

    private static void awaitFastApi(Process process, URI rootUri, Path processLog)
            throws Exception {
        long deadlineNanos = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        Exception lastFailure = null;
        while (System.nanoTime() < deadlineNanos) {
            if (!process.isAlive()) {
                throw new IllegalStateException(
                        "FastAPI exited before becoming ready:\n" + Files.readString(processLog));
            }
            try {
                HttpURLConnection connection = (HttpURLConnection) new URL(rootUri.toString())
                        .openConnection();
                connection.setConnectTimeout(500);
                connection.setReadTimeout(500);
                connection.setRequestMethod("GET");
                if (connection.getResponseCode() == 200) {
                    connection.disconnect();
                    return;
                }
                connection.disconnect();
            } catch (Exception error) {
                lastFailure = error;
            }
            Thread.sleep(100);
        }
        throw new IllegalStateException(
                "FastAPI did not become ready:\n" + Files.readString(processLog), lastFailure);
    }
}
