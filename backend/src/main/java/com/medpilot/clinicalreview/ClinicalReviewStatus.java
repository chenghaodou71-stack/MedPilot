package com.medpilot.clinicalreview;

/** Lifecycle of the human clinical safety gate for one AI triage result. */
public enum ClinicalReviewStatus {
    PENDING_REVIEW,
    IN_REVIEW,
    CLINICIAN_CONFIRMED,
    CLINICIAN_MODIFIED,
    REJECTED,
    EMERGENCY_ESCALATED,
    SYSTEM_FALLBACK
}
