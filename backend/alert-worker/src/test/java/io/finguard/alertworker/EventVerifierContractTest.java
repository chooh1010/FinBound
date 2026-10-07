package io.finguard.alertworker;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

/**
 * 계약이 유효하다고 한 이벤트는 워커도 믿는다. 읽는 쪽이 생산자보다 먼저 새 판정·필드(MASK, 응답 단계)를 받아야 하므로,
 * 계약 fixture를 그대로 넣어 본다. 받아들이지 못하면 워커는 그 자리에서 멈춘다(무결성 실패).
 */
class EventVerifierContractTest {

    private static final Path CONTRACT_EVENTS = Path.of(
            System.getProperty("finguard.repository.root"), "contracts", "events", "fixtures");

    @ParameterizedTest(name = "{0}")
    @MethodSource("validContractEvents")
    void everyValidContractEventIsTrusted(String fixture) throws IOException {
        String eventJson = Files.readString(CONTRACT_EVENTS.resolve(fixture));

        assertThat(EventVerifier.verify(eventJson, EventVerifier.sha256(eventJson)).get("eventId").asText())
                .isNotBlank();
    }

    static Stream<String> validContractEvents() throws IOException {
        try (Stream<Path> files = Files.list(CONTRACT_EVENTS)) {
            return files.map(path -> path.getFileName().toString())
                    .filter(name -> name.endsWith(".valid.json"))
                    .sorted()
                    .toList()
                    .stream();
        }
    }
}
