package com.medpilot.clinicalreview;

/** Explicit clinician action. There is no implicit acceptance of an AI result. */
public enum ClinicalReviewDecision {
    CONFIRM,
    MODIFY,
    REJECT,
    ESCALATE
}
