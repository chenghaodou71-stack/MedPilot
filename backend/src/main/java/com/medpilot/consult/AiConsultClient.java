package com.medpilot.consult;

import reactor.core.publisher.Flux;

import java.util.Map;

public interface AiConsultClient {
    Flux<String> consult(String text, String sessionId, Map<String, String> healthContext);
}
