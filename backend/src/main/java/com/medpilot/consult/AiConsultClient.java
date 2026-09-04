package com.medpilot.consult;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;
import java.util.Map;

public interface AiConsultClient {
    Flux<String> consult(String text, String sessionId, Map<String, String> healthContext);

    /**
     * Opens the upstream response without consuming its body.  Keeping the body as a
     * single cold Flux lets a downstream HTTP cancellation release the AI request.
     */
    default Mono<Flux<String>> openConsult(
            String text,
            String sessionId,
            Map<String, String> healthContext,
            List<HistoryMessage> history) {
        return Mono.just(consult(text, sessionId, healthContext));
    }

    record HistoryMessage(String role, String content) {
        public HistoryMessage {
            if (!List.of("user", "assistant").contains(role)) {
                throw new IllegalArgumentException("history role must be user or assistant");
            }
            if (content == null || content.isBlank()) {
                throw new IllegalArgumentException("history content must not be blank");
            }
        }
    }
}
