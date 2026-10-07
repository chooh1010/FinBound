package io.finguard.audit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * 교차 언어 응답 검사 사례(contracts/response-scan). 위치는 UTF-16 code unit이고 Java String 인덱스와 같다 — 여기서 그것을
 * 확인한다. 같은 파일을 ai-risk(pytest)와 Gateway(JUnit)가 읽는다.
 */
class ResponseScanCasesTest {

    private static final Path CASES = Path.of(
        System.getProperty("finguard.repository.root"), "contracts", "response-scan", "fixtures", "cases.json");
    private static final List<String> CATEGORIES = List.of("RRN", "ACCOUNT_NUMBER", "PHONE_NUMBER", "OTHER_CUSTOMER");
    private static final Map<String, String> LABELS = Map.of(
        "RRN", "[주민등록번호]", "ACCOUNT_NUMBER", "[계좌번호]", "PHONE_NUMBER", "[전화번호]");

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void offsetsCountsAndMaskedTextAgree(String name, JsonNode scanCase) {
        String text = scanCase.get("text").asText();
        List<JsonNode> findings = StreamSupport.stream(scanCase.get("findings").spliterator(), false).toList();
        Map<String, Integer> counted = new LinkedHashMap<>();
        CATEGORIES.forEach(category -> counted.put(category, 0));
        int previousEnd = 0;
        StringBuilder masked = new StringBuilder();
        for (JsonNode finding : findings) {
            int start = finding.get("start").asInt();
            int end = finding.get("end").asInt();
            assertTrue(previousEnd <= start && start < end && end <= text.length(), name + " span " + start + "," + end);
            assertFalse(Character.isLowSurrogate(text.charAt(start)), name + " starts inside a surrogate pair");
            assertFalse(end < text.length() && Character.isLowSurrogate(text.charAt(end)),
                name + " ends inside a surrogate pair");
            assertEquals(finding.get("value").asText(), text.substring(start, end), name);
            String category = finding.get("category").asText();
            counted.merge(category, 1, Integer::sum);
            masked.append(text, previousEnd, start).append(LABELS.getOrDefault(category, ""));
            previousEnd = end;
        }
        masked.append(text.substring(previousEnd));
        CATEGORIES.forEach(category ->
            assertEquals(scanCase.get("counts").get(category).asInt(), counted.get(category), name + " " + category));
        if (counted.get("OTHER_CUSTOMER") > 0) {
            assertNull(scanCase.get("masked").textValue(), name + ": a blocked response has no masked text");
        } else {
            assertEquals(scanCase.get("masked").asText(), masked.toString(), name);
        }
    }

    private static Stream<Object[]> cases() throws IOException {
        JsonNode root = new ObjectMapper().readTree(Files.readString(CASES));
        return StreamSupport.stream(root.get("cases").spliterator(), false)
            .map(scanCase -> new Object[] {scanCase.get("name").asText(), scanCase});
    }
}
