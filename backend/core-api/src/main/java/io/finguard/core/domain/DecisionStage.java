package io.finguard.core.domain;

/**
 * 최종 판정을 내린 단계. docs/04 §19.1.
 *
 * <p>REQUEST는 호출 전 판정이다(5단계 이전의 모든 결과). RESPONSE는 Tool이 돌려준 응답을 검사한 뒤의 판정이다 — 그래서 응답
 * 단계 결과는 언제나 판정이 있고 downstream에 닿았다. 결과 요청에 없으면 REQUEST로 본다(이전 Gateway 호환).
 */
public enum DecisionStage {
    REQUEST,
    RESPONSE
}
