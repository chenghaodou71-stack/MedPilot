package com.medpilot.consult;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class ConsultationMessageService {

    private static final int MAX_MESSAGE_CHARS = 20_000;

    private final ConsultationMessageRepository messages;
    private final ObjectMapper mapper;

    public ConsultationMessageService(
            ConsultationMessageRepository messages,
            ObjectMapper mapper) {
        this.messages = messages;
        this.mapper = mapper;
    }

    @Transactional(readOnly = true)
    public List<AiConsultClient.HistoryMessage> historyFor(Long userId, String sessionId) {
        return ordered(userId, sessionId).stream()
                .map(message -> new AiConsultClient.HistoryMessage(
                        message.getRole(), message.getContent()))
                .toList();
    }

    @Transactional
    public ConsultationMessage appendUser(Long userId, String sessionId, String content) {
        return append(userId, sessionId, "user", content, null);
    }

    @Transactional
    public ConsultationMessage appendAssistant(
            Long userId,
            String sessionId,
            String traceId,
            String content) {
        return append(userId, sessionId, "assistant", content, traceId);
    }

    @Transactional(readOnly = true)
    public String conversationHistoryJson(Long userId, String sessionId) {
        List<Map<String, Object>> payload = ordered(userId, sessionId).stream()
                .map(message -> {
                    Map<String, Object> item = new LinkedHashMap<>();
                    item.put("role", message.getRole());
                    item.put("content", message.getContent());
                    item.put("traceId", message.getTraceId());
                    item.put("createdAt", message.getCreatedAt().toString());
                    return item;
                })
                .toList();
        try {
            return mapper.writeValueAsString(payload);
        } catch (JsonProcessingException exception) {
            throw new IllegalStateException("failed to serialize consultation history", exception);
        }
    }

    private ConsultationMessage append(
            Long userId,
            String sessionId,
            String role,
            String content,
            String traceId) {
        if (userId == null) throw new IllegalArgumentException("user_id is required");
        String canonicalSession = SessionOwnershipService.canonicalUuid(sessionId);
        String normalized = content == null ? "" : content.strip();
        if (normalized.isBlank()) throw new IllegalArgumentException("message content must not be blank");
        if (normalized.length() > MAX_MESSAGE_CHARS) {
            throw new IllegalArgumentException("message content exceeds 20000 characters");
        }
        return messages.save(new ConsultationMessage(
                userId, canonicalSession, role, normalized, traceId));
    }

    private List<ConsultationMessage> ordered(Long userId, String sessionId) {
        if (userId == null) throw new IllegalArgumentException("user_id is required");
        String canonicalSession = SessionOwnershipService.canonicalUuid(sessionId);
        return messages.findBySessionIdAndUserIdOrderByCreatedAtAscIdAsc(
                canonicalSession, userId);
    }
}
