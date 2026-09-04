package com.medpilot.governance;

import org.junit.jupiter.api.Test;

import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class GovernanceEntityTest {

    private static final String SHA = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    @Test
    void modelCannotFreezeBeforeClinicalAndAdversarialGates() {
        ModelRelease release = model();
        assertThrows(IllegalStateException.class, release::freeze);
        release.approve("reviewer-1");
        assertEquals(ModelRelease.APPROVED, release.getStatus());
        release.freeze();
        assertEquals(ModelRelease.FROZEN, release.getStatus());
    }

    @Test
    void evaluationBlocksFalseNegativeAndNeedsIndependentReview() {
        ClinicalEvaluationRun failed = new ClinicalEvaluationRun(
                "run-fail", "rel-1", "dataset-v1", SHA, "去标识化+双人复核", 10,
                .8, .9, 1, 0, .1, "{\"fn\":0}", "file://evaluation.json", "doctor-a");
        assertEquals(ClinicalEvaluationRun.FAILED, failed.getStatus());
        assertThrows(IllegalStateException.class, () -> failed.review("approve", "doctor-b"));

        ClinicalEvaluationRun passed = new ClinicalEvaluationRun(
                "run-pass", "rel-1", "dataset-v1", SHA, "去标识化+双人复核", 10,
                .8, .9, 0, 0, .1, "{\"fn\":0}", "file://evaluation.json", "doctor-a");
        passed.review("approve", "doctor-b");
        assertEquals(ClinicalEvaluationRun.APPROVED, passed.getStatus());
    }

    @Test
    void changeRequesterCannotApproveOwnChange() {
        GovernanceChange change = new GovernanceChange(
                "chg-1", "MODEL_RELEASE", "rel-1", "PROMOTE", "HIGH",
                "模型上线前变更", "评测报告 file://eval", "保留上一版本并可回滚", "operator");
        assertThrows(SecurityException.class, () -> change.approve("operator"));
        change.approve("reviewer");
        change.execute("operator-2");
        change.rollback("operator-3");
        assertEquals(GovernanceChange.ROLLED_BACK, change.getStatus());
    }

    @Test
    void sourceStartsPendingAndExpiresSafely() {
        KnowledgeSourceRegister source = new KnowledgeSourceRegister(
                "src-1", "doc-1", "国家卫生健康委员会", "医疗质量安全核心制度要点",
                "https://www.nhc.gov.cn/example", true, "2025-01-01", "v1", SHA,
                "急诊分诊与院内流程", Instant.now().plusSeconds(3600));
        assertEquals("PENDING", source.getReviewStatus());
        source.review("approve", "clinician");
        assertEquals("APPROVED", source.getReviewStatus());
        assertEquals(true, source.isActiveAt(Instant.now()));
    }

    @Test
    void redTeamAndMonitoringRecordFailClosedSignals() {
        RedTeamTestRun redTeam = new RedTeamTestRun(
                "rt-1", "rel-1", "PROMPT_INJECTION", "red-v1", 10, 9, 1,
                "CRITICAL", "file://red-team.json", "security");
        assertEquals("FAILED", redTeam.getStatus());
        RedTeamTestRun complete = new RedTeamTestRun(
                "rt-2", "rel-1", "MALICIOUS_ATTACHMENT", "red-v1", 10, 10, 0,
                "HIGH", "file://red-team.json", "security");
        assertEquals("PASSED", complete.getStatus());
        RollbackDrillRun drill = new RollbackDrillRun(
                "drill-1", "rel-1", "rel-0", 90, "file://rollback.json", true, "sre");
        assertEquals("PASSED", drill.getStatus());
        ModelMonitoringSnapshot snapshot = new ModelMonitoringSnapshot(
                "mon-1", "rel-1", Instant.now().minusSeconds(60), Instant.now(),
                "psi", .4, .2, 85, 12000, 900,
                "{\"gpu\":{\"utilization_p95\":80}}", "冻结发布并启动复核", "sre");
        assertEquals("ALERT", snapshot.getStatus());
    }

    @Test
    void incidentRequiresCapaToClose() {
        SafetyIncident incident = new SafetyIncident(
                "inc-1", "rel-1", "INCORRECT_ROUTING", "HIGH", "错误分诊被人工发现", "quality",
                Instant.now(), Instant.now().plusSeconds(3600), "file://incident.json");
        assertThrows(IllegalArgumentException.class, () -> incident.close("", ""));
        incident.close("知识索引过期", "撤回版本并重新评测");
        assertEquals("CLOSED", incident.getStatus());
    }

    private static ModelRelease model() {
        return new ModelRelease(
                "rel-1", "qwen2.5", "7b-20260821", SHA, "signature", "ed25519",
                "prompt-v1", "bge-m3-v1", "index-v1", "受控临床试点", "{\"gpu\":{\"memory_mb\":16000}}", "operator");
    }
}
