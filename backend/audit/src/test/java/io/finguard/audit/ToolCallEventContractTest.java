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

/**
 * 도구 호출 이벤트 스트림의 계약. Core가 발행하고 경보·재평가 소비자가 읽는다.
 *
 * <p>소비자가 의존하는 규칙을 고정한다 — 재평가 가능한 이벤트는 판정과 판정 입력을 함께 갖고,
 * fail-closed는 판정을 지어내지 않으며, 고객 식별자 같은 필드는 실리지 않는다.
 */
class ToolCallEventContractTest {

    private static final Path CONTRACT_DIRECTORY = Path.of(
        System.getProperty("finguard.repository.root"),
        "contracts",
        "events"
    );
    private static final String SCHEMA = "tool-call-event.schema.json";

    @ParameterizedTest(name = "{0}")
    @MethodSource("validEvents")
    void acceptsEventThatSatisfiesContract(String scenario, String fixtureFile) throws IOException {
        List<Error> errors = validate(fixtureFile);

        assertTrue(errors.isEmpty(), () -> scenario + " should be valid, but was: " + errors);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidEvents")
    void rejectsEventThatViolatesContract(String scenario, String fixtureFile) throws IOException {
        List<Error> errors = validate(fixtureFile);

        assertFalse(errors.isEmpty(), () -> scenario + " should be rejected");
    }

    private static Stream<Arguments> validEvents() {
        return Stream.of(
            Arguments.of("선저장은 도구와 요청 시각만", "tool-call-event.started.valid.json"),
            Arguments.of("도구 없이 받은 선저장도 그대로", "tool-call-event.started-without-tool.valid.json"),
            Arguments.of("재평가 가능한 확정은 판정과 판정 입력을 함께", "tool-call-event.finalized-eligible.valid.json"),
            Arguments.of("fail-closed 확정은 판정 없이 NO_POLICY_DECISION", "tool-call-event.finalized-fail-closed.valid.json"),
            Arguments.of("판정 입력이 없던 확정은 INPUT_MISSING", "tool-call-event.finalized-input-missing.valid.json"),
            Arguments.of("결과 미도착은 탐지 시각만", "tool-call-event.outcome-unknown.valid.json"),
            Arguments.of("늦은 결과 해소는 탐지·해소 시각과 결과", "tool-call-event.outcome-resolved.valid.json")
        );
    }

    private static Stream<Arguments> invalidEvents() {
        return Stream.of(
            Arguments.of("고객 식별자 차단", "tool-call-event.customer-identifier.invalid.json"),
            Arguments.of("fail-closed에 지어낸 판정 차단", "tool-call-event.fail-closed-with-decision.invalid.json"),
            Arguments.of("판정 입력 없는 ELIGIBLE 차단", "tool-call-event.eligible-without-input.invalid.json"),
            Arguments.of("결과 미도착에 지어낸 판정 차단", "tool-call-event.unknown-with-outcome.invalid.json"),
            Arguments.of("결과 미도착에 지어낸 도달 여부 차단", "tool-call-event.unknown-with-reachability.invalid.json"),
            Arguments.of("판정 입력의 일부 누락 차단", "tool-call-event.partial-policy-input.invalid.json")
        );
    }

    private static List<Error> validate(String fixtureFile) throws IOException {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        Schema schema = registry.getSchema(Files.readString(CONTRACT_DIRECTORY.resolve(SCHEMA)));
        schema.initializeValidators();
        String fixture = Files.readString(CONTRACT_DIRECTORY.resolve("fixtures").resolve(fixtureFile));

        return schema.validate(
            fixture,
            InputFormat.JSON,
            executionContext -> executionContext.executionConfig(
                executionConfig -> executionConfig.formatAssertionsEnabled(true)
            )
        );
    }
}
