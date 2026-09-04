package com.medpilot.governance;

import com.medpilot.common.ApiResponse;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.security.Principal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Hospital change-control API. Every write is append-oriented and identity is
 * taken from the authenticated principal rather than from the request body.
 */
@RestController
@RequestMapping("/api/governance")
public class GovernanceController {

    private final ModelReleaseRepository releases;
    private final ClinicalEvaluationRunRepository evaluations;
    private final KnowledgeSourceRegisterRepository sources;
    private final GovernanceChangeRepository changes;
    private final RedTeamTestRunRepository redTeam;
    private final ModelMonitoringSnapshotRepository monitoring;
    private final SafetyIncidentRepository incidents;
    private final RollbackDrillRunRepository rollbackDrills;

    public GovernanceController(
            ModelReleaseRepository releases,
            ClinicalEvaluationRunRepository evaluations,
            KnowledgeSourceRegisterRepository sources,
            GovernanceChangeRepository changes,
            RedTeamTestRunRepository redTeam,
            ModelMonitoringSnapshotRepository monitoring,
            SafetyIncidentRepository incidents,
            RollbackDrillRunRepository rollbackDrills) {
        this.releases = releases;
        this.evaluations = evaluations;
        this.sources = sources;
        this.changes = changes;
        this.redTeam = redTeam;
        this.monitoring = monitoring;
        this.incidents = incidents;
        this.rollbackDrills = rollbackDrills;
    }

    @PostMapping("/models")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createModel(
            @Valid @RequestBody ModelRequest request, Principal principal) {
        String actor = actor(principal);
        if (releases.findByReleaseId(request.releaseId()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail("release id already exists"));
        }
        ModelRelease release = releases.save(new ModelRelease(
                request.releaseId(), request.modelName(), request.modelVersion(), request.weightSha256(),
                request.artifactSignature(), request.signatureAlgorithm(), request.promptVersion(),
                request.embeddingVersion(), request.knowledgeIndexVersion(), request.scope(),
                request.gpuBaselineJson(), actor));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(modelView(release)));
    }

    @GetMapping("/models")
    public ApiResponse<List<Map<String, Object>>> models() {
        return ApiResponse.ok(releases.findAllByOrderByCreatedAtDesc().stream().map(this::modelView).toList());
    }

    @PostMapping("/models/{releaseId}/approve")
    public ApiResponse<Map<String, Object>> approveModel(@PathVariable String releaseId, Principal principal) {
        ModelRelease release = release(releaseId);
        List<ClinicalEvaluationRun> evaluationRuns = evaluations.findAllByReleaseIdOrderByCreatedAtDesc(releaseId);
        if (evaluationRuns.isEmpty()
                || !ClinicalEvaluationRun.APPROVED.equals(evaluationRuns.get(0).getStatus())) {
            throw new IllegalStateException("model release requires an approved clinical evaluation");
        }
        List<RedTeamTestRun> redTeamRuns = redTeam.findAllByReleaseIdOrderByExecutedAtDesc(releaseId);
        if (redTeamRuns.isEmpty() || !"PASSED".equals(redTeamRuns.get(0).getStatus())) {
            throw new IllegalStateException("model release requires a passing red-team test");
        }
        if (!rollbackDrills.existsByReleaseIdAndStatus(releaseId, "PASSED")) {
            throw new IllegalStateException("model release requires a passing rollback drill");
        }
        release.approve(actor(principal));
        return ApiResponse.ok(modelView(releases.save(release)));
    }

    @PostMapping("/models/{releaseId}/freeze")
    public ApiResponse<Map<String, Object>> freezeModel(@PathVariable String releaseId) {
        ModelRelease release = release(releaseId);
        release.freeze();
        return ApiResponse.ok(modelView(releases.save(release)));
    }

    @PostMapping("/models/{releaseId}/rollback")
    public ApiResponse<Map<String, Object>> rollbackModel(
            @PathVariable String releaseId,
            @Valid @RequestBody RollbackRequest request) {
        ModelRelease target = release(request.targetReleaseId());
        if (ModelRelease.RETIRED.equals(target.getStatus())) {
            throw new IllegalStateException("rollback target is retired");
        }
        ModelRelease release = release(releaseId);
        release.rollbackTo(target.getReleaseId());
        return ApiResponse.ok(modelView(releases.save(release)));
    }

    @PostMapping("/evaluations")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createEvaluation(
            @Valid @RequestBody EvaluationRequest request, Principal principal) {
        release(request.releaseId());
        if (evaluations.findByRunId(request.runId()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail("evaluation run id already exists"));
        }
        ClinicalEvaluationRun run = evaluations.save(new ClinicalEvaluationRun(
                request.runId(), request.releaseId(), request.datasetVersion(), request.datasetSha256(),
                request.deIdentificationMethod(), request.sampleCount(), request.sensitivity(),
                request.specificity(), request.falseNegativeCount(), request.incorrectRoutingCount(),
                request.abstentionRate(), request.thresholdsJson(), request.evidenceUri(), actor(principal)));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(evaluationView(run)));
    }

    @GetMapping("/models/{releaseId}/evaluations")
    public ApiResponse<List<Map<String, Object>>> evaluations(@PathVariable String releaseId) {
        release(releaseId);
        return ApiResponse.ok(evaluations.findAllByReleaseIdOrderByCreatedAtDesc(releaseId).stream().map(this::evaluationView).toList());
    }

    @PostMapping("/evaluations/{runId}/review")
    public ApiResponse<Map<String, Object>> reviewEvaluation(
            @PathVariable String runId, @Valid @RequestBody ReviewRequest request, Principal principal) {
        ClinicalEvaluationRun run = evaluations.findByRunId(runId)
                .orElseThrow(() -> new IllegalArgumentException("evaluation run not found"));
        run.review(request.action(), actor(principal));
        return ApiResponse.ok(evaluationView(evaluations.save(run)));
    }

    @PostMapping("/sources")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createSource(
            @Valid @RequestBody SourceRequest request) {
        if (sources.findBySourceId(request.sourceId()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail("source id already exists"));
        }
        KnowledgeSourceRegister source = sources.save(new KnowledgeSourceRegister(
                request.sourceId(), request.docId(), request.publisher(), request.title(), request.url(),
                request.domesticOfficial(), request.publicationDate(), request.sourceVersion(),
                request.checksum(), request.applicableScope(), request.expiresAt()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(sourceView(source)));
    }

    @GetMapping("/sources")
    public ApiResponse<List<Map<String, Object>>> sources() {
        return ApiResponse.ok(sources.findAllByOrderByCreatedAtDesc().stream().map(this::sourceView).toList());
    }

    @PostMapping("/sources/{sourceId}/review")
    public ApiResponse<Map<String, Object>> reviewSource(
            @PathVariable String sourceId, @Valid @RequestBody ReviewRequest request, Principal principal) {
        KnowledgeSourceRegister source = sources.findBySourceId(sourceId)
                .orElseThrow(() -> new IllegalArgumentException("knowledge source not found"));
        source.review(request.action(), actor(principal));
        return ApiResponse.ok(sourceView(sources.save(source)));
    }

    @PostMapping("/changes")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createChange(
            @Valid @RequestBody ChangeRequest request, Principal principal) {
        if (changes.findByChangeId(request.changeId()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail("change id already exists"));
        }
        GovernanceChange change = changes.save(new GovernanceChange(
                request.changeId(), request.targetType(), request.targetId(), request.changeType(),
                request.riskLevel(), request.reason(), request.validationEvidence(), request.rollbackPlan(), actor(principal)));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(changeView(change)));
    }

    @GetMapping("/changes")
    public ApiResponse<List<Map<String, Object>>> changes() {
        return ApiResponse.ok(changes.findAllByOrderByRequestedAtDesc().stream().map(this::changeView).toList());
    }

    @PostMapping("/changes/{changeId}/{action}")
    public ApiResponse<Map<String, Object>> changeAction(
            @PathVariable String changeId, @PathVariable String action, Principal principal) {
        GovernanceChange change = changes.findByChangeId(changeId)
                .orElseThrow(() -> new IllegalArgumentException("governance change not found"));
        String normalized = action == null ? "" : action.strip().toLowerCase();
        if ("approve".equals(normalized)) change.approve(actor(principal));
        else if ("reject".equals(normalized)) change.reject(actor(principal));
        else if ("execute".equals(normalized)) change.execute(actor(principal));
        else if ("rollback".equals(normalized)) change.rollback(actor(principal));
        else throw new IllegalArgumentException("unsupported change action");
        return ApiResponse.ok(changeView(changes.save(change)));
    }

    @PostMapping("/red-team")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createRedTeam(
            @Valid @RequestBody RedTeamRequest request, Principal principal) {
        release(request.releaseId());
        if (redTeam.findByTestId(request.testId()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail("red-team test id already exists"));
        }
        RedTeamTestRun run = redTeam.save(new RedTeamTestRun(
                request.testId(), request.releaseId(), request.testType(), request.datasetVersion(),
                request.caseCount(), request.blockedCount(), request.escapedCount(), request.severity(),
                request.reportUri(), actor(principal)));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(redTeamView(run)));
    }

    @GetMapping("/models/{releaseId}/red-team")
    public ApiResponse<List<Map<String, Object>>> redTeam(@PathVariable String releaseId) {
        release(releaseId);
        return ApiResponse.ok(redTeam.findAllByReleaseIdOrderByExecutedAtDesc(releaseId).stream().map(this::redTeamView).toList());
    }

    @PostMapping("/rollback-drills")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createRollbackDrill(
            @Valid @RequestBody RollbackDrillRequest request, Principal principal) {
        release(request.releaseId());
        release(request.rollbackTargetReleaseId());
        if (request.releaseId().equals(request.rollbackTargetReleaseId())) {
            throw new IllegalArgumentException("rollback target must differ from the tested release");
        }
        if (rollbackDrills.findByDrillId(request.drillId()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail("rollback drill id already exists"));
        }
        RollbackDrillRun drill = rollbackDrills.save(new RollbackDrillRun(
                request.drillId(), request.releaseId(), request.rollbackTargetReleaseId(),
                request.recoveryDurationSeconds(), request.evidenceUri(), request.dataIntegrityCheck(), actor(principal)));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(rollbackDrillView(drill)));
    }

    @GetMapping("/models/{releaseId}/rollback-drills")
    public ApiResponse<List<Map<String, Object>>> rollbackDrills(@PathVariable String releaseId) {
        release(releaseId);
        return ApiResponse.ok(rollbackDrills.findAllByReleaseIdOrderByDrilledAtDesc(releaseId).stream()
                .map(this::rollbackDrillView).toList());
    }

    @PostMapping("/monitoring")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createMonitoring(
            @Valid @RequestBody MonitoringRequest request, Principal principal) {
        release(request.releaseId());
        if (monitoring.findBySnapshotId(request.snapshotId()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail("monitoring snapshot id already exists"));
        }
        ModelMonitoringSnapshot snapshot = monitoring.save(new ModelMonitoringSnapshot(
                request.snapshotId(), request.releaseId(), request.windowStart(), request.windowEnd(),
                request.driftMetric(), request.driftScore(), request.driftThreshold(), request.gpuUtilizationP95(),
                request.gpuMemoryP95Mb(), request.queueLatencyP95Ms(), request.capacityBaselineJson(),
                request.actionTaken(), actor(principal)));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(monitoringView(snapshot)));
    }

    @GetMapping("/models/{releaseId}/monitoring")
    public ApiResponse<List<Map<String, Object>>> monitoring(@PathVariable String releaseId) {
        release(releaseId);
        return ApiResponse.ok(monitoring.findAllByReleaseIdOrderByObservedAtDesc(releaseId).stream()
                .map(this::monitoringView).toList());
    }

    @PostMapping("/incidents")
    public ResponseEntity<ApiResponse<Map<String, Object>>> createIncident(
            @Valid @RequestBody IncidentRequest request) {
        release(request.releaseId());
        if (incidents.findByIncidentId(request.incidentId()).isPresent()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail("safety incident id already exists"));
        }
        SafetyIncident incident = incidents.save(new SafetyIncident(
                request.incidentId(), request.releaseId(), request.incidentType(), request.severity(),
                request.summary(), request.owner(), request.detectedAt(), request.dueAt(), request.evidenceUri()));
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(incidentView(incident)));
    }

    @PostMapping("/incidents/{incidentId}/close")
    public ApiResponse<Map<String, Object>> closeIncident(
            @PathVariable String incidentId, @Valid @RequestBody CloseIncidentRequest request) {
        SafetyIncident incident = incidents.findByIncidentId(incidentId)
                .orElseThrow(() -> new IllegalArgumentException("safety incident not found"));
        incident.close(request.rootCause(), request.correctiveAction());
        return ApiResponse.ok(incidentView(incidents.save(incident)));
    }

    @GetMapping("/incidents")
    public ApiResponse<List<Map<String, Object>>> incidents() {
        return ApiResponse.ok(incidents.findAllByOrderByDetectedAtDesc().stream().map(this::incidentView).toList());
    }

    @GetMapping("/summary")
    public ApiResponse<Map<String, Object>> summary() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelReleases", releases.count());
        result.put("approvedEvaluations", evaluations.countByStatus(ClinicalEvaluationRun.APPROVED));
        result.put("pendingSources", sources.countByReviewStatus("PENDING"));
        result.put("openChanges", changes.countByStatus(GovernanceChange.DRAFT));
        result.put("failedRedTeamRuns", redTeam.countByStatus("FAILED"));
        result.put("passedRollbackDrills", rollbackDrills.count());
        result.put("openSafetyIncidents", incidents.countByStatusNot("CLOSED"));
        result.put("generatedAt", Instant.now().toString());
        return ApiResponse.ok(result);
    }

    @ExceptionHandler(IllegalStateException.class)
    public ResponseEntity<ApiResponse<Void>> conflict(IllegalStateException exception) {
        return ResponseEntity.status(HttpStatus.CONFLICT).body(ApiResponse.fail(exception.getMessage()));
    }

    private ModelRelease release(String releaseId) {
        return releases.findByReleaseId(releaseId)
                .orElseThrow(() -> new IllegalArgumentException("model release not found"));
    }

    private static String actor(Principal principal) {
        if (principal == null || principal.getName() == null || principal.getName().isBlank()) {
            throw new SecurityException("authenticated governance identity is required");
        }
        return principal.getName().strip();
    }

    private Map<String, Object> modelView(ModelRelease item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("releaseId", item.getReleaseId()); result.put("modelName", item.getModelName());
        result.put("modelVersion", item.getModelVersion()); result.put("weightSha256", item.getWeightSha256());
        result.put("artifactSignature", item.getArtifactSignature()); result.put("signatureAlgorithm", item.getSignatureAlgorithm());
        result.put("promptVersion", item.getPromptVersion()); result.put("embeddingVersion", item.getEmbeddingVersion());
        result.put("knowledgeIndexVersion", item.getKnowledgeIndexVersion()); result.put("scope", item.getScope());
        result.put("gpuBaselineJson", item.getGpuBaselineJson()); result.put("status", item.getStatus());
        result.put("rollbackTargetReleaseId", item.getRollbackTargetReleaseId()); result.put("createdBy", item.getCreatedBy());
        result.put("approvedBy", item.getApprovedBy()); result.put("approvedAt", item.getApprovedAt()); result.put("frozenAt", item.getFrozenAt());
        result.put("createdAt", item.getCreatedAt()); return result;
    }

    private Map<String, Object> evaluationView(ClinicalEvaluationRun item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("runId", item.getRunId()); result.put("releaseId", item.getReleaseId()); result.put("datasetVersion", item.getDatasetVersion());
        result.put("datasetSha256", item.getDatasetSha256()); result.put("deIdentificationMethod", item.getDeIdentificationMethod());
        result.put("sampleCount", item.getSampleCount()); result.put("sensitivity", item.getSensitivity()); result.put("specificity", item.getSpecificity());
        result.put("falseNegativeCount", item.getFalseNegativeCount()); result.put("incorrectRoutingCount", item.getIncorrectRoutingCount());
        result.put("abstentionRate", item.getAbstentionRate()); result.put("thresholdsJson", item.getThresholdsJson()); result.put("status", item.getStatus());
        result.put("evidenceUri", item.getEvidenceUri()); result.put("evaluatedBy", item.getEvaluatedBy()); result.put("reviewedBy", item.getReviewedBy());
        result.put("reviewedAt", item.getReviewedAt()); result.put("createdAt", item.getCreatedAt()); return result;
    }

    private Map<String, Object> sourceView(KnowledgeSourceRegister item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("sourceId", item.getSourceId()); result.put("docId", item.getDocId()); result.put("publisher", item.getPublisher());
        result.put("title", item.getTitle()); result.put("url", item.getUrl()); result.put("domesticOfficial", item.isDomesticOfficial());
        result.put("publicationDate", item.getPublicationDate()); result.put("sourceVersion", item.getSourceVersion()); result.put("checksum", item.getChecksum());
        result.put("applicableScope", item.getApplicableScope()); result.put("reviewStatus", item.getReviewStatus()); result.put("reviewer", item.getReviewer());
        result.put("reviewedAt", item.getReviewedAt()); result.put("expiresAt", item.getExpiresAt()); return result;
    }

    private Map<String, Object> changeView(GovernanceChange item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("changeId", item.getChangeId()); result.put("targetType", item.getTargetType()); result.put("targetId", item.getTargetId());
        result.put("changeType", item.getChangeType()); result.put("riskLevel", item.getRiskLevel()); result.put("reason", item.getReason());
        result.put("validationEvidence", item.getValidationEvidence()); result.put("rollbackPlan", item.getRollbackPlan()); result.put("status", item.getStatus());
        result.put("requestedBy", item.getRequestedBy()); result.put("approvedBy", item.getApprovedBy()); result.put("requestedAt", item.getRequestedAt());
        result.put("approvedAt", item.getApprovedAt()); result.put("executedAt", item.getExecutedAt()); result.put("rolledBackAt", item.getRolledBackAt()); return result;
    }

    private Map<String, Object> redTeamView(RedTeamTestRun item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("testId", item.getTestId()); result.put("releaseId", item.getReleaseId()); result.put("testType", item.getTestType());
        result.put("datasetVersion", item.getDatasetVersion()); result.put("caseCount", item.getCaseCount()); result.put("blockedCount", item.getBlockedCount());
        result.put("escapedCount", item.getEscapedCount()); result.put("severity", item.getSeverity()); result.put("reportUri", item.getReportUri());
        result.put("status", item.getStatus()); result.put("executedBy", item.getExecutedBy()); result.put("executedAt", item.getExecutedAt()); return result;
    }

    private Map<String, Object> rollbackDrillView(RollbackDrillRun item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("drillId", item.getDrillId()); result.put("releaseId", item.getReleaseId());
        result.put("rollbackTargetReleaseId", item.getRollbackTargetReleaseId()); result.put("status", item.getStatus());
        result.put("recoveryDurationSeconds", item.getRecoveryDurationSeconds()); result.put("evidenceUri", item.getEvidenceUri());
        result.put("dataIntegrityCheck", item.isDataIntegrityCheck()); result.put("drilledBy", item.getDrilledBy()); result.put("drilledAt", item.getDrilledAt());
        return result;
    }

    private Map<String, Object> monitoringView(ModelMonitoringSnapshot item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshotId", item.getSnapshotId()); result.put("releaseId", item.getReleaseId()); result.put("windowStart", item.getWindowStart());
        result.put("windowEnd", item.getWindowEnd()); result.put("driftMetric", item.getDriftMetric()); result.put("driftScore", item.getDriftScore());
        result.put("driftThreshold", item.getDriftThreshold()); result.put("gpuUtilizationP95", item.getGpuUtilizationP95()); result.put("gpuMemoryP95Mb", item.getGpuMemoryP95Mb());
        result.put("queueLatencyP95Ms", item.getQueueLatencyP95Ms()); result.put("capacityBaselineJson", item.getCapacityBaselineJson()); result.put("status", item.getStatus());
        result.put("actionTaken", item.getActionTaken()); result.put("observedBy", item.getObservedBy()); result.put("observedAt", item.getObservedAt()); return result;
    }

    private Map<String, Object> incidentView(SafetyIncident item) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("incidentId", item.getIncidentId()); result.put("releaseId", item.getReleaseId()); result.put("incidentType", item.getIncidentType());
        result.put("severity", item.getSeverity()); result.put("summary", item.getSummary()); result.put("rootCause", item.getRootCause());
        result.put("correctiveAction", item.getCorrectiveAction()); result.put("status", item.getStatus()); result.put("owner", item.getOwner());
        result.put("detectedAt", item.getDetectedAt()); result.put("dueAt", item.getDueAt()); result.put("closedAt", item.getClosedAt()); result.put("evidenceUri", item.getEvidenceUri()); return result;
    }

    public record ModelRequest(
            @NotBlank @Size(max = 128) String releaseId,
            @NotBlank @Size(max = 128) String modelName,
            @NotBlank @Size(max = 128) String modelVersion,
            @NotBlank @Size(min = 64, max = 64) String weightSha256,
            @NotBlank @Size(max = 4096) String artifactSignature,
            @NotBlank @Size(max = 64) String signatureAlgorithm,
            @NotBlank @Size(max = 128) String promptVersion,
            @NotBlank @Size(max = 128) String embeddingVersion,
            @NotBlank @Size(max = 128) String knowledgeIndexVersion,
            @NotBlank @Size(max = 1024) String scope,
            @NotBlank @Size(max = 100_000) String gpuBaselineJson) { }

    public record RollbackRequest(@NotBlank @Size(max = 128) String targetReleaseId) { }

    public record ReviewRequest(@NotBlank @Size(max = 16) String action) { }

    public record EvaluationRequest(
            @NotBlank @Size(max = 128) String runId,
            @NotBlank @Size(max = 128) String releaseId,
            @NotBlank @Size(max = 128) String datasetVersion,
            @NotBlank @Size(min = 64, max = 64) String datasetSha256,
            @NotBlank @Size(max = 512) String deIdentificationMethod,
            @Min(1) int sampleCount,
            @DecimalMin("0.0") @DecimalMax("1.0") double sensitivity,
            @DecimalMin("0.0") @DecimalMax("1.0") double specificity,
            @Min(0) int falseNegativeCount,
            @Min(0) int incorrectRoutingCount,
            @DecimalMin("0.0") @DecimalMax("1.0") double abstentionRate,
            @NotBlank @Size(max = 100_000) String thresholdsJson,
            @NotBlank @Size(max = 2048) String evidenceUri) { }

    public record SourceRequest(
            @NotBlank @Size(max = 128) String sourceId,
            @NotBlank @Size(max = 128) String docId,
            @NotBlank @Size(max = 256) String publisher,
            @NotBlank @Size(max = 512) String title,
            @NotBlank @Size(max = 2048) String url,
            boolean domesticOfficial,
            @NotBlank @Size(max = 10) String publicationDate,
            @NotBlank @Size(max = 256) String sourceVersion,
            @NotBlank @Size(min = 64, max = 64) String checksum,
            @NotBlank @Size(max = 1024) String applicableScope,
            Instant expiresAt) { }

    public record ChangeRequest(
            @NotBlank @Size(max = 128) String changeId,
            @NotBlank @Size(max = 64) String targetType,
            @NotBlank @Size(max = 128) String targetId,
            @NotBlank @Size(max = 64) String changeType,
            @NotBlank @Size(max = 16) String riskLevel,
            @NotBlank @Size(max = 20_000) String reason,
            @NotBlank @Size(max = 20_000) String validationEvidence,
            @NotBlank @Size(max = 20_000) String rollbackPlan) { }

    public record RedTeamRequest(
            @NotBlank @Size(max = 128) String testId,
            @NotBlank @Size(max = 128) String releaseId,
            @NotBlank @Size(max = 64) String testType,
            @NotBlank @Size(max = 128) String datasetVersion,
            @Min(1) int caseCount,
            @Min(0) int blockedCount,
            @Min(0) int escapedCount,
            @NotBlank @Size(max = 16) String severity,
            @NotBlank @Size(max = 2048) String reportUri) { }

    public record RollbackDrillRequest(
            @NotBlank @Size(max = 128) String drillId,
            @NotBlank @Size(max = 128) String releaseId,
            @NotBlank @Size(max = 128) String rollbackTargetReleaseId,
            @Min(0) int recoveryDurationSeconds,
            @NotBlank @Size(max = 2048) String evidenceUri,
            boolean dataIntegrityCheck) { }

    public record MonitoringRequest(
            @NotBlank @Size(max = 128) String snapshotId,
            @NotBlank @Size(max = 128) String releaseId,
            Instant windowStart,
            Instant windowEnd,
            @NotBlank @Size(max = 64) String driftMetric,
            @DecimalMin("0.0") double driftScore,
            @DecimalMin("0.0") double driftThreshold,
            @DecimalMin("0.0") @DecimalMax("100.0") double gpuUtilizationP95,
            @DecimalMin("0.0") double gpuMemoryP95Mb,
            @DecimalMin("0.0") double queueLatencyP95Ms,
            @NotBlank @Size(max = 100_000) String capacityBaselineJson,
            @NotBlank @Size(max = 20_000) String actionTaken) { }

    public record IncidentRequest(
            @NotBlank @Size(max = 128) String incidentId,
            @NotBlank @Size(max = 128) String releaseId,
            @NotBlank @Size(max = 64) String incidentType,
            @NotBlank @Size(max = 16) String severity,
            @NotBlank @Size(max = 20_000) String summary,
            @NotBlank @Size(max = 128) String owner,
            Instant detectedAt,
            Instant dueAt,
            @Size(max = 2048) String evidenceUri) { }

    public record CloseIncidentRequest(
            @NotBlank @Size(max = 20_000) String rootCause,
            @NotBlank @Size(max = 20_000) String correctiveAction) { }
}
