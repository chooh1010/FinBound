package io.finguard.gateway.identity;

/**
 * 검증된 Agent.
 *
 * <p>{@code credentialId}는 어느 설정 Credential로 인증했는지다(원문이 아니라 설정 순번). 지금은 모든 Credential이 같은
 * {@code agentId}로 매핑되므로, 호출자를 구분하려면 이 값까지 봐야 한다 — 예: Request ID 재사용 판정(docs/04 §17).
 */
public record VerifiedAgentIdentity(String agentId, String credentialStatus, String credentialId) {

    public static final String ATTRIBUTE_KEY = "finguard.verifiedAgentIdentity";

    public static VerifiedAgentIdentity verified(String agentId) {
        return verified(agentId, agentId);
    }

    public static VerifiedAgentIdentity verified(String agentId, String credentialId) {
        return new VerifiedAgentIdentity(agentId, "VERIFIED", credentialId);
    }
}
