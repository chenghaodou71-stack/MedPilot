package com.medpilot.consult;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.orm.jpa.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({ConsultationMessageService.class, ConsultationPersistenceService.class,
        ConsultTestObjectMapperConfig.class})
@ActiveProfiles("test")
class ConsultationMessagePersistenceTest {

    private static final String SESSION_ID = "1779673a-c983-47e4-9715-f2d9548f469a";
    private static final String TRACE_ID = "2c293933-6590-4bfc-b0e8-507d3063c90b";

    @Autowired ConsultationMessageService messages;
    @Autowired ConsultationMessageRepository messageRepository;
    @Autowired ConsultationPersistenceService persistence;
    @Autowired ConsultationRecordRepository records;
    @Autowired ConsultationTraceRepository traces;
    @Autowired ObjectMapper mapper;

    @BeforeEach
    void clear() {
        records.deleteAll();
        traces.deleteAll();
        messageRepository.deleteAll();
    }

    @Test
    void returnsPersistedPriorTurnsInChronologicalAiHistoryFormat() {
        messages.appendUser(2L, SESSION_ID, "第一轮症状");
        messages.appendAssistant(2L, SESSION_ID, TRACE_ID, "第一轮追问");

        List<AiConsultClient.HistoryMessage> history = messages.historyFor(2L, SESSION_ID);

        assertThat(history).containsExactly(
                new AiConsultClient.HistoryMessage("user", "第一轮症状"),
                new AiConsultClient.HistoryMessage("assistant", "第一轮追问"));
    }

    @Test
    void successfulTurnPersistsAssistantMessageAndFullConversationOnTheRecord() throws Exception {
        messages.appendUser(2L, SESSION_ID, "第一轮症状");
        messages.appendAssistant(2L, SESSION_ID, "1c293933-6590-4bfc-b0e8-507d3063c90b", "第一轮追问");
        messages.appendUser(2L, SESSION_ID, "已经持续三天");

        var snapshot = new ConsultationEventAccumulator.Snapshot(
                TRACE_ID, SESSION_ID, "头痛三天", "神经内科", "中", 0.8,
                "建议尽快就诊", null, "建议前往神经内科。", "[]", "[]",
                false, "completed", true);
        persistence.persist(2L, "已经持续三天", "{}", snapshot);

        List<ConsultationMessage> stored = messageRepository
                .findBySessionIdAndUserIdOrderByCreatedAtAscIdAsc(SESSION_ID, 2L);
        assertThat(stored).extracting(ConsultationMessage::getRole)
                .containsExactly("user", "assistant", "user", "assistant");
        assertThat(stored.get(3).getContent()).isEqualTo("建议前往神经内科。");

        JsonNode history = mapper.readTree(records.findByTraceId(TRACE_ID)
                .orElseThrow().getConversationHistory());
        assertThat(history).hasSize(4);
        assertThat(history.get(0).path("content").asText()).isEqualTo("第一轮症状");
        assertThat(history.get(3).path("role").asText()).isEqualTo("assistant");
    }

    @Test
    void followupQuestionIsPersistedEvenWhenNoClinicalRecordIsCreated() {
        messages.appendUser(2L, SESSION_ID, "头痛");
        var snapshot = new ConsultationEventAccumulator.Snapshot(
                TRACE_ID, SESSION_ID, "头痛", null, null, null, null, null,
                "持续多久了？", "[]", "[]", true, "completed", false);

        persistence.persist(2L, "头痛", "{}", snapshot);

        assertThat(messageRepository.findBySessionIdAndUserIdOrderByCreatedAtAscIdAsc(
                SESSION_ID, 2L)).extracting(ConsultationMessage::getContent)
                .containsExactly("头痛", "持续多久了？");
        assertThat(records.findByTraceId(TRACE_ID)).isEmpty();
    }
}
