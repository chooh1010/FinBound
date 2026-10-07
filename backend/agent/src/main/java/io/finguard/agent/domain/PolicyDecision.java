package io.finguard.agent.domain;

public enum PolicyDecision {
    ALLOW,
    BLOCK,
    /** 사람의 확인이 필요해 Gateway가 실행하지 않았다. 승인 후 재개는 아직 없다 — docs/04 §5. */
    APPROVAL,
    /** 실행했고, 응답 속 개인정보를 범주 표시로 가려 돌려줬다. 자유 텍스트 Tool의 응답 단계에서만 나온다 — docs/04 §19. */
    MASK;

    /** 이 판정이 Tool을 실행하게 하는가. 실행하지 않는 판정은 사유가 있어야 하고 금융 결과가 없다. */
    public boolean runsTool() {
        return this == ALLOW || this == MASK;
    }
}
