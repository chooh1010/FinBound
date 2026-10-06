package io.finguard.audit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

class AuditRuntimeContractTest {

    private static final Path CONTRACT_DIRECTORY = Path.of(
        System.getProperty("finguard.repository.root"),
        "contracts",
        "audit"
    );

    @ParameterizedTest(name = "{0}")
    @MethodSource("validContracts")
    void acceptsDocumentThatSatisfiesContract(String scenario, String schemaFile, String fixtureFile)
        throws IOException {
        List<Error> errors = validate(schemaFile, fixtureFile);

        assertTrue(errors.isEmpty(), () -> scenario + " should be valid, but was: " + errors);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidContracts")
    void rejectsDocumentThatViolatesContract(String scenario, String schemaFile, String fixtureFile)
        throws IOException {
        List<Error> errors = validate(schemaFile, fixtureFile);

        assertFalse(errors.isEmpty(), () -> scenario + " should be rejected");
    }

    private static Stream<Arguments> validContracts() {
        return Stream.of(
            Arguments.of(
                "ToolCallAttempt에는 현재 시점의 값만 기록",
                "tool-call-attempt.schema.json",
                "tool-call-attempt.valid.json"
            ),
            Arguments.of(
                "ALLOW 완료 시 Downstream과 응답 반환",
                "execution-outcome.schema.json",
                "execution-outcome.allow.valid.json"
            ),
            Arguments.of(
                "BLOCK 완료 시 Downstream 미도달",
                "execution-outcome.schema.json",
                "execution-outcome.block.valid.json"
            ),
            Arguments.of(
                "APPROVAL은 실행하지 않고 승인을 기다림",
                "execution-outcome.schema.json",
                "execution-outcome.approval.valid.json"
            ),
            Arguments.of(
                "시스템 장애는 Decision이 아닌 ERROR Outcome",
                "execution-outcome.schema.json",
                "execution-outcome.error.valid.json"
            ),
            Arguments.of(
                "정책 판정 전 fail-closed는 decision을 생략하고 ERROR로만 기록",
                "execution-outcome.schema.json",
                "execution-outcome.fail-closed.valid.json"
            ),
            Arguments.of(
                "판정 입력 null은 생략과 같다(fail-closed에서도 허용)",
                "execution-outcome.schema.json",
                "execution-outcome.fail-closed-null-policy-input.valid.json"
            ),
            Arguments.of(
                "정책 판정에 닿은 결과는 판정 입력 스냅샷을 함께 기록",
                "execution-outcome.schema.json",
                "execution-outcome.allow-with-policy-input.valid.json"
            ),
            Arguments.of(
                "승인을 써서 허용된 결과는 판정 입력에 approvalGranted를 남김",
                "execution-outcome.schema.json",
                "execution-outcome.allow-with-approval.valid.json"
            ),
            Arguments.of(
                "승인 없이 판정한 결과는 approvalGranted=false",
                "execution-outcome.schema.json",
                "execution-outcome.approval-not-granted.valid.json"
            ),
            Arguments.of(
                "판정 전에 실패해도 이미 쓴 승인과 연결은 남음",
                "audit-event.schema.json",
                "audit-event.error-with-consumed-approval.valid.json"
            ),
            Arguments.of(
                "인증 성공 직후 PROCESSING Business Audit 생성",
                "audit-event.schema.json",
                "audit-event.processing.valid.json"
            ),
            Arguments.of(
                "ALLOW Business Audit 완료",
                "audit-event.schema.json",
                "audit-event.allow.valid.json"
            ),
            Arguments.of(
                "BLOCK Business Audit도 COMPLETED 상태",
                "audit-event.schema.json",
                "audit-event.block.valid.json"
            ),
            Arguments.of(
                "APPROVAL 감사 행은 실행 측정값 없이 확정",
                "audit-event.schema.json",
                "audit-event.approval.valid.json"
            ),
            Arguments.of(
                "Core 장애는 ERROR Business Audit으로 완료",
                "audit-event.schema.json",
                "audit-event.error.valid.json"
            ),
            Arguments.of(
                "결과 미도착은 Core가 결과 필드 없이 OUTCOME_UNKNOWN으로 기록",
                "audit-event.schema.json",
                "audit-event.outcome-unknown.valid.json"
            ),
            Arguments.of(
                "늦게 도착한 결과로 확정해도 탐지 시각을 보존",
                "audit-event.schema.json",
                "audit-event.outcome-resolved.valid.json"
            ),
            Arguments.of(
                "인증 실패는 최소 SecurityAuthEvent로 분리",
                "security-auth-event.schema.json",
                "security-auth-event.valid.json"
            )
        );
    }

    private static Stream<Arguments> invalidContracts() {
        return Stream.of(
            Arguments.of(
                "ToolCallAttempt의 미래 실행값 차단",
                "tool-call-attempt.schema.json",
                "tool-call-attempt.future-values.invalid.json"
            ),
            Arguments.of(
                "Decision ERROR 차단",
                "execution-outcome.schema.json",
                "execution-outcome.invalid-decision.json"
            ),
            Arguments.of(
                "BLOCK의 Downstream 도달 차단",
                "execution-outcome.schema.json",
                "execution-outcome.block-reached-downstream.invalid.json"
            ),
            Arguments.of(
                "APPROVAL인데 Downstream에 도달하고 측정값을 보냄",
                "execution-outcome.schema.json",
                "execution-outcome.approval-reached-downstream.invalid.json"
            ),
            Arguments.of(
                "BLOCK ExecutionOutcome의 success 차단",
                "execution-outcome.schema.json",
                "execution-outcome.block-execution-values.invalid.json"
            ),
            Arguments.of(
                "BLOCK ExecutionOutcome의 recordsRead 차단",
                "execution-outcome.schema.json",
                "execution-outcome.block-records-read.invalid.json"
            ),
            Arguments.of(
                "BLOCK ExecutionOutcome의 latencyMs 차단",
                "execution-outcome.schema.json",
                "execution-outcome.block-latency.invalid.json"
            ),
            Arguments.of(
                "BLOCK과 ERROR systemOutcome의 동시 기록 차단",
                "execution-outcome.schema.json",
                "execution-outcome.block-with-error.invalid.json"
            ),
            Arguments.of(
                "정책 판정의 severity와 riskFlagged 누락 차단",
                "execution-outcome.schema.json",
                "execution-outcome.policy-risk.invalid.json"
            ),
            Arguments.of(
                "Business Audit의 민감 원문 차단",
                "audit-event.schema.json",
                "audit-event.sensitive-data.invalid.json"
            ),
            Arguments.of(
                "BLOCK Business Audit의 실행 측정값 차단",
                "audit-event.schema.json",
                "audit-event.block-execution-values.invalid.json"
            ),
            Arguments.of(
                "APPROVAL 감사 행에 실행 측정값",
                "audit-event.schema.json",
                "audit-event.approval-execution-values.invalid.json"
            ),
            Arguments.of(
                "판정이 있는 감사 행에 Severity·Risk Flag가 없음",
                "audit-event.schema.json",
                "audit-event.approval-without-severity.invalid.json"
            ),
            Arguments.of(
                "OUTCOME_UNKNOWN에 지어낸 판정·도달 여부 차단",
                "audit-event.schema.json",
                "audit-event.outcome-unknown-invented-decision.invalid.json"
            ),
            Arguments.of(
                "OUTCOME_UNKNOWN의 탐지 시각 누락 차단",
                "audit-event.schema.json",
                "audit-event.outcome-unknown-without-detection.invalid.json"
            ),
            Arguments.of(
                "탐지 없이 해소 시각만 있는 기록 차단",
                "audit-event.schema.json",
                "audit-event.outcome-resolved-without-detection.invalid.json"
            ),
            Arguments.of(
                "탐지된 적 있는 확정 기록의 해소 시각 누락 차단",
                "audit-event.schema.json",
                "audit-event.detected-final-without-resolution.invalid.json"
            ),
            Arguments.of(
                "PROCESSING의 탐지 시각 차단",
                "audit-event.schema.json",
                "audit-event.processing-with-detection.invalid.json"
            ),
            Arguments.of(
                "fail-closed 결과의 판정 입력 차단",
                "execution-outcome.schema.json",
                "execution-outcome.fail-closed-with-policy-input.invalid.json"
            ),
            Arguments.of(
                "판정 입력 스냅샷의 일부 누락 차단",
                "execution-outcome.schema.json",
                "execution-outcome.policy-input-partial.invalid.json"
            ),
            Arguments.of(
                "approvalGranted가 boolean이 아님",
                "execution-outcome.schema.json",
                "execution-outcome.approval-granted-not-boolean.invalid.json"
            ),
            Arguments.of(
                "approvalGranted가 null",
                "execution-outcome.schema.json",
                "execution-outcome.approval-granted-null.invalid.json"
            ),
            Arguments.of(
                "빈 approvalRequestId",
                "audit-event.schema.json",
                "audit-event.approval-request-id-empty.invalid.json"
            ),
            Arguments.of(
                "Gateway 결과 입력의 OUTCOME_UNKNOWN 차단",
                "execution-outcome.schema.json",
                "execution-outcome.outcome-unknown.invalid.json"
            ),
            Arguments.of(
                "인증 실패 Event의 Business Audit 필드 차단",
                "security-auth-event.schema.json",
                "security-auth-event.business-audit-fields.invalid.json"
            ),
            Arguments.of(
                "인증 실패 Event의 민감 원문 차단",
                "security-auth-event.schema.json",
                "security-auth-event.sensitive-data.invalid.json"
            )
        );
    }

    private static List<Error> validate(String schemaFile, String fixtureFile) throws IOException {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        String schemaDocument = Files.readString(CONTRACT_DIRECTORY.resolve(schemaFile));
        String fixtureDocument = Files.readString(
            CONTRACT_DIRECTORY.resolve("fixtures").resolve(fixtureFile)
        );
        Schema schema = registry.getSchema(schemaDocument);
        schema.initializeValidators();

        return schema.validate(
            fixtureDocument,
            InputFormat.JSON,
            executionContext -> executionContext.executionConfig(
                executionConfig -> executionConfig.formatAssertionsEnabled(true)
            )
        );
    }
}
