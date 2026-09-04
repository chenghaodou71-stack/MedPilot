package com.medpilot.governance;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

import java.time.Instant;

/** Periodic drift and GPU-capacity evidence; values are observations, not clinical claims. */
@Entity
@Table(name = "model_monitoring_snapshots", indexes = {
        @Index(name = "idx_monitoring_release_observed", columnList = "release_id,observed_at"),
        @Index(name = "idx_monitoring_status", columnList = "monitoring_status")
})
public class ModelMonitoringSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "snapshot_id", nullable = false, unique = true, length = 128)
    private String snapshotId;

    @Column(name = "release_id", nullable = false, length = 128)
    private String releaseId;

    @Column(name = "window_start", nullable = false)
    private Instant windowStart;

    @Column(name = "window_end", nullable = false)
    private Instant windowEnd;

    @Column(name = "drift_metric", nullable = false, length = 64)
    private String driftMetric;

    @Column(name = "drift_score", nullable = false)
    private double driftScore;

    @Column(name = "drift_threshold", nullable = false)
    private double driftThreshold;

    @Column(name = "gpu_utilization_p95", nullable = false)
    private double gpuUtilizationP95;

    @Column(name = "gpu_memory_p95_mb", nullable = false)
    private double gpuMemoryP95Mb;

    @Column(name = "queue_latency_p95_ms", nullable = false)
    private double queueLatencyP95Ms;

    @Column(name = "capacity_baseline_json", nullable = false, columnDefinition = "LONGTEXT")
    private String capacityBaselineJson;

    @Column(name = "monitoring_status", nullable = false, length = 32)
    private String status;

    @Column(name = "action_taken", nullable = false, columnDefinition = "LONGTEXT")
    private String actionTaken;

    @Column(name = "observed_by", nullable = false, length = 128)
    private String observedBy;

    @Column(name = "observed_at", nullable = false)
    private Instant observedAt = Instant.now();

    protected ModelMonitoringSnapshot() {
    }

    public ModelMonitoringSnapshot(
            String snapshotId,
            String releaseId,
            Instant windowStart,
            Instant windowEnd,
            String driftMetric,
            double driftScore,
            double driftThreshold,
            double gpuUtilizationP95,
            double gpuMemoryP95Mb,
            double queueLatencyP95Ms,
            String capacityBaselineJson,
            String actionTaken,
            String observedBy) {
        this.snapshotId = code(snapshotId, 128, "snapshot id");
        this.releaseId = code(releaseId, 128, "release id");
        this.windowStart = windowStart == null ? Instant.now().minusSeconds(3600) : windowStart;
        this.windowEnd = windowEnd == null ? Instant.now() : windowEnd;
        if (!this.windowEnd.isAfter(this.windowStart)) throw new IllegalArgumentException("monitoring window is invalid");
        this.driftMetric = code(driftMetric, 64, "drift metric");
        this.driftScore = nonNegative(driftScore, "drift score");
        this.driftThreshold = nonNegative(driftThreshold, "drift threshold");
        this.gpuUtilizationP95 = percentage(gpuUtilizationP95, "GPU utilization");
        this.gpuMemoryP95Mb = nonNegative(gpuMemoryP95Mb, "GPU memory");
        this.queueLatencyP95Ms = nonNegative(queueLatencyP95Ms, "queue latency");
        this.capacityBaselineJson = code(capacityBaselineJson, 100_000, "capacity baseline");
        this.status = driftScore > driftThreshold ? "ALERT" : "WITHIN_BASELINE";
        this.actionTaken = code(actionTaken, 20_000, "monitoring action");
        this.observedBy = code(observedBy, 128, "observer");
        this.observedAt = Instant.now();
    }

    public Long getId() { return id; }
    public String getSnapshotId() { return snapshotId; }
    public String getReleaseId() { return releaseId; }
    public Instant getWindowStart() { return windowStart; }
    public Instant getWindowEnd() { return windowEnd; }
    public String getDriftMetric() { return driftMetric; }
    public double getDriftScore() { return driftScore; }
    public double getDriftThreshold() { return driftThreshold; }
    public double getGpuUtilizationP95() { return gpuUtilizationP95; }
    public double getGpuMemoryP95Mb() { return gpuMemoryP95Mb; }
    public double getQueueLatencyP95Ms() { return queueLatencyP95Ms; }
    public String getCapacityBaselineJson() { return capacityBaselineJson; }
    public String getStatus() { return status; }
    public String getActionTaken() { return actionTaken; }
    public String getObservedBy() { return observedBy; }
    public Instant getObservedAt() { return observedAt; }

    private static String code(String value, int max, String field) {
        String normalized = value == null ? "" : value.strip();
        if (normalized.isEmpty() || normalized.length() > max) throw new IllegalArgumentException(field + " is required and too long");
        return normalized;
    }

    private static double nonNegative(double value, String field) {
        if (Double.isNaN(value) || value < 0) throw new IllegalArgumentException(field + " cannot be negative");
        return value;
    }

    private static double percentage(double value, String field) {
        if (Double.isNaN(value) || value < 0 || value > 100) throw new IllegalArgumentException(field + " must be between 0 and 100");
        return value;
    }
}
