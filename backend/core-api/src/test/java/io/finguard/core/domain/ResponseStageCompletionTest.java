package io.finguard.core.domain;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

/** 응답 단계 상태 표(docs/04 §19.1). 계약 fixture와 같은 행·같은 위반을 도메인 결과에서 본다. */
class ResponseStageCompletionTest {

    private static final Instant AT = Instant.parse("2026-10-07T12:00:01Z");
    private static final ResponseScan CLEAN = new ResponseScan("response-scan-1", "response-policy-1", 0, 0, 0, 0);
    private static final ResponseScan PII = new ResponseScan("response-scan-1", "response-policy-1", 1, 0, 2, 0);
    private static final ResponseScan OTHER = new ResponseScan("response-scan-1", "response-policy-1", 1, 0, 0, 1);

    @ParameterizedTest(name = "{0}")
    @MethodSource("validRows")
    void eachRowOfTheStateTableIsAccepted(String row, Draft draft) {
        assertThatCode(draft::build).doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidRows")
    void eachWayToBreakTheTableIsRejected(String row, Draft draft) {
        assertThatThrownBy(draft::build).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void scanEvidenceAcceptsOnlyAllowedVersionsAndBoundedCounts() {
        assertThatThrownBy(() -> new ResponseScan("response-scan-1 900101-1234567", "response-policy-1", 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResponseScan("response-scan-1", "loan-review-policy-4", 0, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResponseScan("response-scan-1", "response-policy-1", 257, 0, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ResponseScan("response-scan-1", "response-policy-1", 0, -1, 0, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    static Stream<Arguments> validRows() {
        return Stream.of(
                Arguments.of("clean response", allow()),
                Arguments.of("masked response", mask()),
                Arguments.of("response-stage block", responseBlock()),
                Arguments.of("failed scan", responseError()),
                Arguments.of("scan switch off", scanDisabled()),
                Arguments.of("request-stage block, stage omitted", requestBlock().with(d -> d.stage(null))),
                Arguments.of("request-stage allow, stage omitted",
                        allow().with(d -> d.stage(null).scan(null))));
    }

    static Stream<Arguments> invalidRows() {
        Map<String, Draft> rows = new java.util.LinkedHashMap<>();
        rows.put("mask without reasons", mask().with(d -> d.reasons(Set.of())));
        rows.put("mask with error", mask().with(d -> d.outcome(AuditStatus.ERROR).released(false).success(false)
                .location("AI_RISK").scan(null).records(null)));
        rows.put("mask at request stage", mask().with(d -> d.stage(DecisionStage.REQUEST).scan(null)));
        rows.put("mask without scan", mask().with(d -> d.scan(null)));
        rows.put("clean response without scan", allow().with(d -> d.scan(null)));
        rows.put("clean response read 42", allow().with(d -> d.records(42)));
        rows.put("clean response without records", allow().with(d -> d.records(null)));
        rows.put("response block read a record", responseBlock().with(d -> d.records(1)));
        rows.put("response block released", responseBlock().with(d -> d.released(true)));
        rows.put("response block succeeded", responseBlock().with(d -> d.success(true)));
        rows.put("response block without latency", responseBlock().with(d -> d.latency(null)));
        rows.put("response block not reached", responseBlock().with(d -> d.reached(false)));
        rows.put("response block without reasons", responseBlock().with(d -> d.reasons(Set.of())));
        rows.put("failed scan with counts", responseError().with(d -> d.scan(CLEAN)));
        rows.put("failed scan read a record", responseError().with(d -> d.records(1)));
        rows.put("request stage with scan", allow().with(d -> d.stage(DecisionStage.REQUEST)));
        rows.put("approval at response stage", requestBlock().with(d -> d.decision(PolicyDecision.APPROVAL)
                .stage(DecisionStage.RESPONSE)));
        rows.put("no decision at response stage", scanDisabled().with(d -> d.stage(DecisionStage.RESPONSE)
                .reached(true).reasons(Set.of("RESPONSE_SCAN_UNAVAILABLE"))));
        rows.put("scan off but reached", scanDisabled().with(d -> d.reached(true)));
        rows.put("scan off with a decision", scanDisabled().with(d -> d.decision(PolicyDecision.ALLOW)
                .severity(Severity.LOW).flagged(false)));
        rows.put("scan off elsewhere", scanDisabled().with(d -> d.location("AI_RISK")));
        rows.put("scan off measured", scanDisabled().with(d -> d.latency(5L)));
        rows.put("request block reached", requestBlock().with(d -> d.reached(true)));
        return rows.entrySet().stream().map(entry -> Arguments.of(entry.getKey(), entry.getValue()));
    }

    private static Draft allow() {
        return new Draft(PolicyDecision.ALLOW, AuditStatus.COMPLETED, Set.of(), true, true, true, 1, 140L, null,
                Severity.LOW, false, DecisionStage.RESPONSE, CLEAN);
    }

    private static Draft mask() {
        return allow().with(d -> d.decision(PolicyDecision.MASK)
                .reasons(Set.of("RRN_MASKED", "PHONE_NUMBER_MASKED")).scan(PII));
    }

    private static Draft responseBlock() {
        return new Draft(PolicyDecision.BLOCK, AuditStatus.COMPLETED, Set.of("OTHER_CUSTOMER_DATA_IN_RESPONSE"), true,
                false, false, null, 150L, null, Severity.HIGH, true, DecisionStage.RESPONSE, OTHER);
    }

    private static Draft responseError() {
        return new Draft(PolicyDecision.ALLOW, AuditStatus.ERROR, Set.of("RESPONSE_SCAN_UNAVAILABLE"), true, false,
                false, null, 1600L, "AI_RISK", Severity.LOW, false, DecisionStage.RESPONSE, null);
    }

    private static Draft scanDisabled() {
        return new Draft(null, AuditStatus.ERROR, Set.of("RESPONSE_SCAN_DISABLED"), false, false, false, null, null,
                "GATEWAY", null, null, DecisionStage.REQUEST, null);
    }

    private static Draft requestBlock() {
        return new Draft(PolicyDecision.BLOCK, AuditStatus.COMPLETED, Set.of("CASE_SCOPE_VIOLATION"), false, false,
                null, null, null, null, Severity.CRITICAL, true, DecisionStage.REQUEST, null);
    }

    /** 결과 하나의 초안. 한 칸씩 바꿔 표의 위반을 만든다. */
    record Draft(
            PolicyDecision decision,
            AuditStatus outcome,
            Set<String> reasons,
            boolean reached,
            boolean released,
            Boolean success,
            Integer records,
            Long latency,
            String location,
            Severity severity,
            Boolean flagged,
            DecisionStage stage,
            ResponseScan scan) {

        Draft with(UnaryOperator<Draft> change) {
            return change.apply(this);
        }

        Draft decision(PolicyDecision value) {
            return new Draft(value, outcome, reasons, reached, released, success, records, latency, location,
                    severity, flagged, stage, scan);
        }

        Draft outcome(AuditStatus value) {
            return new Draft(decision, value, reasons, reached, released, success, records, latency, location,
                    severity, flagged, stage, scan);
        }

        Draft reasons(Set<String> value) {
            return new Draft(decision, outcome, value, reached, released, success, records, latency, location,
                    severity, flagged, stage, scan);
        }

        Draft reached(boolean value) {
            return new Draft(decision, outcome, reasons, value, released, success, records, latency, location,
                    severity, flagged, stage, scan);
        }

        Draft released(boolean value) {
            return new Draft(decision, outcome, reasons, reached, value, success, records, latency, location,
                    severity, flagged, stage, scan);
        }

        Draft success(Boolean value) {
            return new Draft(decision, outcome, reasons, reached, released, value, records, latency, location,
                    severity, flagged, stage, scan);
        }

        Draft records(Integer value) {
            return new Draft(decision, outcome, reasons, reached, released, success, value, latency, location,
                    severity, flagged, stage, scan);
        }

        Draft latency(Long value) {
            return new Draft(decision, outcome, reasons, reached, released, success, records, value, location,
                    severity, flagged, stage, scan);
        }

        Draft location(String value) {
            return new Draft(decision, outcome, reasons, reached, released, success, records, latency, value,
                    severity, flagged, stage, scan);
        }

        Draft severity(Severity value) {
            return new Draft(decision, outcome, reasons, reached, released, success, records, latency, location,
                    value, flagged, stage, scan);
        }

        Draft flagged(Boolean value) {
            return new Draft(decision, outcome, reasons, reached, released, success, records, latency, location,
                    severity, value, stage, scan);
        }

        Draft stage(DecisionStage value) {
            return new Draft(decision, outcome, reasons, reached, released, success, records, latency, location,
                    severity, flagged, value, scan);
        }

        Draft scan(ResponseScan value) {
            return new Draft(decision, outcome, reasons, reached, released, success, records, latency, location,
                    severity, flagged, stage, value);
        }

        AuditCompletion build() {
            return new AuditCompletion(decision, outcome, reasons, reached, released, success, records, latency,
                    location, null, severity, flagged, decision == null ? null : "loan-review-policy-4", AT, null,
                    stage, scan);
        }
    }
}
