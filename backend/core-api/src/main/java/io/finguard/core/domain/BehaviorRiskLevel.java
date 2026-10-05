package io.finguard.core.domain;

/** ai-risk 행동 위험 등급. OPA 입력 {@code risk.behaviorRiskLevel}과 같은 값이다 — docs/04 §10. */
public enum BehaviorRiskLevel {
    LOW,
    ALERT,
    CRITICAL,
}
