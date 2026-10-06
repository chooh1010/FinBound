package io.finguard.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.networknt.schema.Error;
import com.networknt.schema.InputFormat;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;

/**
 * 이벤트 v2 계약(contracts/events). fixture 이름이 기대를 말한다 — {@code *.valid.json}은 통과, {@code *.invalid.json}은
 * 거부. 디렉터리를 그대로 읽으므로 fixture를 더하면 테스트도 따라 늘어난다.
 */
class EventContractTest {

    private static final Path EVENTS_DIRECTORY = Path.of(
        System.getProperty("finguard.repository.root"),
        "contracts",
        "events"
    );
    private static final String SCHEMA_FILE = "finguard-event-v2.schema.json";
    // 봉투 eventType의 enum 배열. 공백·줄바꿈과 무관하게 찾는다.
    private static final Pattern SCHEMA_ENUM =
        Pattern.compile("\"eventType\"\\s*:\\s*\\{\\s*\"type\"\\s*:\\s*\"string\"\\s*,\\s*\"enum\"\\s*:\\s*\\[([^\\]]*)\\]");
    private static final Pattern FIXTURE_TYPE = Pattern.compile("\"eventType\"\\s*:\\s*(\"[A-Z_]+\")");

    @ParameterizedTest(name = "{0}")
    @MethodSource("validFixtures")
    void acceptsEventThatSatisfiesContract(String fixtureFile) throws IOException {
        List<Error> errors = validate(fixtureFile);

        assertTrue(errors.isEmpty(), () -> fixtureFile + " should be valid, but was: " + errors);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidFixtures")
    void rejectsEventThatViolatesContract(String fixtureFile) throws IOException {
        List<Error> errors = validate(fixtureFile);

        assertFalse(errors.isEmpty(), () -> fixtureFile + " should be rejected");
    }

    @Test
    void coversEveryEventTypeWithAValidFixture() throws IOException {
        // 스키마 enum이 기준이다. 타입을 더하고 fixture를 잊으면 여기서 걸린다.
        Set<String> declared = eventTypes(SCHEMA_ENUM, Files.readString(EVENTS_DIRECTORY.resolve(SCHEMA_FILE)));
        Set<String> covered = new TreeSet<>();
        for (String name : validFixtures().toList()) {
            String fixture = Files.readString(EVENTS_DIRECTORY.resolve("fixtures").resolve(name));
            covered.addAll(eventTypes(FIXTURE_TYPE, fixture));
        }

        assertEquals(9, declared.size(), () -> "unexpected schema enum " + declared);
        assertEquals(declared, covered);
    }

    private static Set<String> eventTypes(Pattern pattern, String document) {
        Set<String> types = new TreeSet<>();
        Matcher matcher = pattern.matcher(document);
        while (matcher.find()) {
            Matcher names = Pattern.compile("[A-Z][A-Z_]+").matcher(matcher.group(1));
            while (names.find()) {
                types.add(names.group());
            }
        }
        return types;
    }

    private static Stream<String> validFixtures() throws IOException {
        return fixtures(".valid.json");
    }

    private static Stream<String> invalidFixtures() throws IOException {
        return fixtures(".invalid.json");
    }

    private static Stream<String> fixtures(String suffix) throws IOException {
        try (Stream<Path> files = Files.list(EVENTS_DIRECTORY.resolve("fixtures"))) {
            return files.map(path -> path.getFileName().toString())
                .filter(name -> name.endsWith(suffix))
                .sorted()
                .toList()
                .stream();
        }
    }

    private static List<Error> validate(String fixtureFile) throws IOException {
        SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);
        Schema schema = registry.getSchema(Files.readString(EVENTS_DIRECTORY.resolve(SCHEMA_FILE)));
        schema.initializeValidators();

        return schema.validate(
            Files.readString(EVENTS_DIRECTORY.resolve("fixtures").resolve(fixtureFile)),
            InputFormat.JSON,
            executionContext -> executionContext.executionConfig(
                executionConfig -> executionConfig.formatAssertionsEnabled(true)
            )
        );
    }
}
