package com.medpilot.consult;

import com.medpilot.security.EncryptedStringConverter;
import jakarta.persistence.*;
import java.time.Instant;

/** 问诊记录：记录完整多轮对话、分诊结果、生成内容。关联 user_id 做权限隔离。 */
@Entity
@Table(name = "consultation_records")
public class ConsultationRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "session_id", nullable = false, length = 64)
    private String sessionId;

    @Column(name = "trace_id", unique = true, length = 36)
    private String traceId;

    @Column(name = "symptoms", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String symptoms;

    @Column(name = "department", length = 64)
    private String department;

    @Column(name = "risk_level", length = 16)
    private String riskLevel;

    @Column(name = "confidence")
    private Double confidence;

    @Column(name = "support_score")
    private Double supportScore;

    @Column(name = "abstained", nullable = false)
    private boolean abstained;

    @Column(name = "urgency", columnDefinition = "TEXT")
    private String urgency;

    @Column(name = "matched_rule", length = 128)
    private String matchedRule;

    @Column(name = "triage_factors", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String triageFactors;

    @Column(name = "explanation", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String explanation;

    @Column(name = "answer", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String answer;

    @Column(name = "citations", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String citations;

    @Column(name = "conversation_history", columnDefinition = "TEXT")
    @Convert(converter = EncryptedStringConverter.class)
    private String conversationHistory;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    protected ConsultationRecord() {
    }

    public ConsultationRecord(Long userId, String sessionId) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.createdAt = Instant.now();
    }

    public Long getId() { return id; }
    public Long getUserId() { return userId; }
    public String getSessionId() { return sessionId; }
    public String getTraceId() { return traceId; }
    public String getSymptoms() { return symptoms; }
    public String getDepartment() { return department; }
    public String getRiskLevel() { return riskLevel; }
    public Double getConfidence() { return confidence; }
    public Double getSupportScore() { return supportScore; }
    public boolean isAbstained() { return abstained; }
    public String getUrgency() { return urgency; }
    public String getMatchedRule() { return matchedRule; }
    public String getTriageFactors() { return triageFactors; }
    public String getExplanation() { return explanation; }
    public String getAnswer() { return answer; }
    public String getCitations() { return citations; }
    public String getConversationHistory() { return conversationHistory; }
    public Instant getCreatedAt() { return createdAt; }

    public void setSymptoms(String symptoms) { this.symptoms = symptoms; }
    public void setTraceId(String traceId) { this.traceId = traceId; }
    public void setDepartment(String department) { this.department = department; }
    public void setRiskLevel(String riskLevel) { this.riskLevel = riskLevel; }
    public void setConfidence(Double confidence) { this.confidence = confidence; }
    public void setSupportScore(Double supportScore) { this.supportScore = supportScore; }
    public void setAbstained(boolean abstained) { this.abstained = abstained; }
    public void setUrgency(String urgency) { this.urgency = urgency; }
    public void setMatchedRule(String matchedRule) { this.matchedRule = matchedRule; }
    public void setTriageFactors(String triageFactors) { this.triageFactors = triageFactors; }
    public void setExplanation(String explanation) { this.explanation = explanation; }
    public void setAnswer(String answer) { this.answer = answer; }
    public void setCitations(String citations) { this.citations = citations; }
    public void setConversationHistory(String conversationHistory) { this.conversationHistory = conversationHistory; }
}
