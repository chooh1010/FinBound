package io.finguard.core.security;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.NotBlank;

/**
 * 브라우저(Vue) → Core {@code /api/v1/**} 인증 설정. {@code docs/04-api-contract.md} §2.
 *
 * <p>셋 중 하나라도 비어 있으면 기동에 실패한다. 인증 없는 {@code /api/v1/**}가 떠 있는 상태를
 * 만들지 않는다.
 *
 * <p>두 Credential이 같으면 역할 구분이 사라져 Viewer가 AgentRun을 생성할 수 있게 된다.
 * 설정 실수로 권한 경계가 조용히 무너지는 경로라 기동을 막는다.
 */
@Validated
@ConfigurationProperties(prefix = "finguard.api")
public record CoreApiProperties(
        @NotBlank String viewerCredential,
        @NotBlank String operatorCredential,
        @NotBlank String operatorEmployeeId,
        String approverCredential,
        String approverEmployeeId) {

    /**
     * 승인자는 선택이다. Credential과 직원 ID가 둘 다 있을 때만 APPROVER 역할이 생긴다(docs/04 §2).
     */
    public boolean approverConfigured() {
        return hasText(approverCredential) && hasText(approverEmployeeId);
    }

    /** 하나만 있으면 설정 실수다. 승인자가 있는 줄 알았는데 없는 상태를 만들지 않는다. */
    @AssertTrue(message = "approver credential and approver employee id must be set together")
    public boolean hasCompleteApproverOrNone() {
        return hasText(approverCredential) == hasText(approverEmployeeId);
    }

    /** 승인자 Credential이 다른 역할과 같으면 그 역할로 승인할 수 있게 된다. */
    @AssertTrue(message = "approver credential must differ from the viewer and operator credentials")
    public boolean hasDistinctApproverCredential() {
        return !hasText(approverCredential)
                || (!approverCredential.equals(viewerCredential) && !approverCredential.equals(operatorCredential));
    }

    /** 직무 분리: 업무를 요청하는 직원이 그 승인까지 하면 사람의 확인이 아니게 된다. */
    @AssertTrue(message = "approver employee must differ from the operator employee")
    public boolean hasSeparateApproverEmployee() {
        return !hasText(approverEmployeeId) || !approverEmployeeId.equals(operatorEmployeeId);
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    @AssertTrue(message = "viewer and operator credentials must differ")
    public boolean hasDistinctCredentials() {
        return viewerCredential == null
                || operatorCredential == null
                || !viewerCredential.equals(operatorCredential);
    }
}
