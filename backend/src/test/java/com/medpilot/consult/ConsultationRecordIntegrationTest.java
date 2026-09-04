package com.medpilot.consult;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.medpilot.auth.JwtAuthFilter;
import jakarta.servlet.http.Cookie;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ConsultationRecordIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    ObjectMapper mapper;

    @Autowired
    ConsultationRecordRepository recordRepo;

    private Cookie adminCookie;
    private Cookie userCookie;

    @BeforeEach
    void setup() throws Exception {
        recordRepo.deleteAll();
        adminCookie = login("admin", "admin123");
        userCookie = login("user", "user123");
    }

    private Cookie login(String username, String password) throws Exception {
        var res = mvc.perform(post("/api/auth/login")
                        .with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn();
        return res.getResponse().getCookie(JwtAuthFilter.AUTH_COOKIE_NAME);
    }

    @Test
    void normalUserSeesOnlyOwnRecords() throws Exception {
        // 创建两条记录：一条属于 user(id=2)，一条属于 admin(id=1)
        ConsultationRecord r1 = new ConsultationRecord(2L, "sess-user");
        r1.setSymptoms("头痛");
        r1.setDepartment("神经内科");
        r1.setUrgency("建议尽快就医");
        recordRepo.save(r1);

        ConsultationRecord r2 = new ConsultationRecord(1L, "sess-admin");
        r2.setSymptoms("胸痛");
        r2.setDepartment("心血管内科");
        recordRepo.save(r2);

        // user 只能看到自己的
        mvc.perform(get("/api/records").cookie(userCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].symptoms").value("头痛"))
                .andExpect(jsonPath("$.data[0].urgency").value("建议尽快就医"));
    }

    @Test
    void administratorSeesOnlyOwnRecordsWithoutAClinicalRelationship() throws Exception {
        ConsultationRecord r1 = new ConsultationRecord(2L, "sess-user");
        r1.setSymptoms("头痛");
        recordRepo.save(r1);

        ConsultationRecord r2 = new ConsultationRecord(1L, "sess-admin");
        r2.setSymptoms("胸痛");
        recordRepo.save(r2);

        // 管理员不再获得患者记录的无边界读取权限。
        mvc.perform(get("/api/records").cookie(adminCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].symptoms").value("胸痛"));
    }

    @Test
    void searchBySymptoms() throws Exception {
        ConsultationRecord r1 = new ConsultationRecord(2L, "s1");
        r1.setSymptoms("头痛");
        recordRepo.save(r1);

        ConsultationRecord r2 = new ConsultationRecord(2L, "s2");
        r2.setSymptoms("咳嗽");
        recordRepo.save(r2);

        mvc.perform(get("/api/records?symptoms=头痛").cookie(userCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].symptoms").value("头痛"));
    }

    @Test
    void searchByDepartment() throws Exception {
        ConsultationRecord r1 = new ConsultationRecord(2L, "s1");
        r1.setDepartment("神经内科");
        recordRepo.save(r1);

        ConsultationRecord r2 = new ConsultationRecord(2L, "s2");
        r2.setDepartment("呼吸内科");
        recordRepo.save(r2);

        mvc.perform(get("/api/records?department=神经内科").cookie(userCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].department").value("神经内科"));
    }

    @Test
    void searchByTimeRange() throws Exception {
        Instant past = Instant.now().minus(10, ChronoUnit.DAYS);
        ConsultationRecord r1 = new ConsultationRecord(2L, "s1");
        recordRepo.save(r1);

        mvc.perform(get("/api/records?startTime=" + past.toString())
                        .cookie(userCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void combinesRecordAndSessionIdentifiersSymptomsDepartmentEndTimeAndPagination() throws Exception {
        ConsultationRecord target = new ConsultationRecord(
                2L, "1779673a-c983-47e4-9715-f2d9548f469a");
        target.setSymptoms("持续头痛三天");
        target.setDepartment("神经内科");
        recordRepo.saveAndFlush(target);

        ConsultationRecord otherSymptom = new ConsultationRecord(
                2L, "2779673a-c983-47e4-9715-f2d9548f469a");
        otherSymptom.setSymptoms("咳嗽");
        otherSymptom.setDepartment("呼吸内科");
        recordRepo.saveAndFlush(otherSymptom);

        String start = Instant.now().minus(1, ChronoUnit.HOURS).toString();
        String end = Instant.now().plus(1, ChronoUnit.HOURS).toString();
        mvc.perform(get("/api/records")
                        .param("id", target.getId().toString())
                        .param("sessionId", "1779673a")
                        .param("symptoms", "头痛")
                        .param("department", "神经内科")
                        .param("startTime", start)
                        .param("endTime", end)
                        .param("page", "0")
                        .param("size", "1")
                        .cookie(userCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(target.getId()))
                .andExpect(jsonPath("$.meta.page").value(0))
                .andExpect(jsonPath("$.meta.size").value(1))
                .andExpect(jsonPath("$.meta.total").value(1))
                .andExpect(jsonPath("$.meta.pages").value(1));
    }

    @Test
    void paginatesOnTheServerAndRejectsUnboundedPageSizes() throws Exception {
        for (int index = 0; index < 3; index++) {
            ConsultationRecord record = new ConsultationRecord(2L, "session-" + index);
            record.setSymptoms("头痛" + index);
            recordRepo.save(record);
        }

        mvc.perform(get("/api/records?page=1&size=2").cookie(userCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.meta.total").value(3))
                .andExpect(jsonPath("$.meta.pages").value(2));

        mvc.perform(get("/api/records?page=0&size=101").cookie(userCookie))
                .andExpect(status().isBadRequest());
    }

    @Test
    void keywordSearchMatchesRecordIdSessionSymptomsOrDepartmentOnTheServer() throws Exception {
        ConsultationRecord target = new ConsultationRecord(
                2L, "1779673a-c983-47e4-9715-f2d9548f469a");
        target.setSymptoms("持续偏头痛");
        target.setDepartment("神经内科");
        recordRepo.saveAndFlush(target);
        ConsultationRecord other = new ConsultationRecord(
                2L, "2779673a-c983-47e4-9715-f2d9548f469a");
        other.setSymptoms("持续咳嗽");
        other.setDepartment("呼吸内科");
        recordRepo.saveAndFlush(other);

        mvc.perform(get("/api/records").param("query", "偏头痛").cookie(userCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].id").value(target.getId()));

        mvc.perform(get("/api/records").param("keyword", target.getId().toString())
                        .cookie(userCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1))
                .andExpect(jsonPath("$.data[0].sessionId").value(target.getSessionId()));
    }

    @Test
    void detailReturnsFullRecord() throws Exception {
        ConsultationRecord r = new ConsultationRecord(2L, "sess-detail");
        r.setTraceId("2c293933-6590-4bfc-b0e8-507d3063c90b");
        r.setSymptoms("头痛");
        r.setDepartment("神经内科");
        r.setRiskLevel("中");
        r.setConfidence(0.75);
        r.setSupportScore(0.82);
        r.setAbstained(false);
        r.setExplanation("检索证据主要支持神经内科；该分数不是临床准确率。");
        r.setTriageFactors("[{\"kind\":\"evidence\",\"label\":\"指南\",\"support\":0.82}]");
        r.setAnswer("建议挂号神经内科");
        r.setCitations("[{\"citation_id\":\"doc#0\",\"source\":\"source1\",\"quote\":\"原文摘录\"}]");
        r.setConversationHistory("{\"text\":\"我头痛\"}");
        recordRepo.save(r);

        mvc.perform(get("/api/records/" + r.getId()).cookie(userCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.symptoms").value("头痛"))
                .andExpect(jsonPath("$.data.department").value("神经内科"))
                .andExpect(jsonPath("$.data.riskLevel").value("中"))
                .andExpect(jsonPath("$.data.confidence").value(0.75))
                .andExpect(jsonPath("$.data.supportScore").value(0.82))
                .andExpect(jsonPath("$.data.abstained").value(false))
                .andExpect(jsonPath("$.data.explanation").value("检索证据主要支持神经内科；该分数不是临床准确率。"))
                .andExpect(jsonPath("$.data.triageFactors[0].kind").value("evidence"))
                .andExpect(jsonPath("$.data.answer").value("建议挂号神经内科"))
                .andExpect(jsonPath("$.data.traceId").value("2c293933-6590-4bfc-b0e8-507d3063c90b"))
                .andExpect(jsonPath("$.data.citations[0].citation_id").value("doc#0"))
                .andExpect(jsonPath("$.data.citations[0].quote").value("原文摘录"))
                .andExpect(jsonPath("$.data.conversationHistory").value("{\"text\":\"我头痛\"}"));
    }

    @Test
    void detailConvertsLegacyCitationStringToObjects() throws Exception {
        ConsultationRecord record = new ConsultationRecord(2L, "legacy-citations");
        record.setCitations("source1, source2");
        recordRepo.save(record);

        mvc.perform(get("/api/records/" + record.getId())
                        .cookie(userCookie))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.citations[0].source").value("source1"))
                .andExpect(jsonPath("$.data.citations[1].source").value("source2"));
    }

    @Test
    void normalUserCannotAccessOthersDetail() throws Exception {
        ConsultationRecord r = new ConsultationRecord(1L, "sess-admin-only");
        recordRepo.save(r);

        mvc.perform(get("/api/records/" + r.getId()).cookie(userCookie))
                .andExpect(status().isForbidden()); // GlobalExceptionHandler 返回403
    }

    @Test
    void administratorCannotAccessAnotherUsersRecordWithoutAClinicalRelationship() throws Exception {
        ConsultationRecord r = new ConsultationRecord(2L, "sess-user-data");
        r.setSymptoms("咳嗽");
        recordRepo.save(r);

        mvc.perform(get("/api/records/" + r.getId()).cookie(adminCookie))
                .andExpect(status().isForbidden());
    }
}
