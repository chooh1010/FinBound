package io.finguard.gateway.response;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Pattern;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.databind.JsonNode;

import io.finguard.gateway.contract.FinancialTool;
import io.finguard.gateway.contract.PolicyDecision;
import lombok.extern.slf4j.Slf4j;

/**
 * 자유 텍스트 Tool의 응답을 내보내기 전에 검사한다. docs/04 §19.
 *
 * <p>순서: 크기 → 탐지기(ai-risk) → 탐지 응답 검증 → 응답 정책(OPA) → 판정 검증 → 가리기. 어느 단계든 실패하거나 서로
 * 모순되면 {@link Inspection#failed}다 — 원문을 내보내는 실패 경로는 없다. 판정은 OPA가 내리고 여기서는 검증만 한다.
 *
 * <p>원문, 탐지 값, 위치는 로그·예외 메시지에 남기지 않는다. 남기는 것은 요청 id와 고정 사유 코드뿐이다.
 */
@Slf4j
@Component
public class ResponseInspector {

    /** 응답을 검사하는 Tool. 요청한 Tool로 판단한다 — 응답이 주장하는 Tool이 아니다. */
    static final Set<FinancialTool> TEXT_TOOLS = EnumSet.of(FinancialTool.LOAN_APPLICATION_READ);

    static final List<String> CATEGORIES = List.of("RRN", "ACCOUNT_NUMBER", "PHONE_NUMBER", "OTHER_CUSTOMER");
    static final Map<String, String> LABELS = Map.of(
        "RRN", "[주민등록번호]", "ACCOUNT_NUMBER", "[계좌번호]", "PHONE_NUMBER", "[전화번호]");
    static final Map<String, String> MASK_REASONS = Map.of(
        "RRN", "RRN_MASKED", "ACCOUNT_NUMBER", "ACCOUNT_NUMBER_MASKED", "PHONE_NUMBER", "PHONE_NUMBER_MASKED");
    static final String OTHER_CUSTOMER_REASON = "OTHER_CUSTOMER_DATA_IN_RESPONSE";

    private static final int MAX_FINDINGS = 256;
    private static final int MAX_COUNT = 256;
    private static final Pattern DETECTOR_VERSION = Pattern.compile("^response-scan-[0-9]{1,4}$");
    private static final Pattern POLICY_VERSION = Pattern.compile("^response-policy-[0-9]{1,4}$");
    private static final Set<String> SEVERITIES = Set.of("LOW", "MEDIUM", "HIGH", "CRITICAL");
    private static final Set<String> SCAN_FIELDS = Set.of("detectorVersion", "findings", "counts");
    private static final Set<String> FINDING_FIELDS = Set.of("category", "start", "end");

    private final ResponseScanClient scanClient;
    private final ResponsePolicyClient policyClient;
    private final ResponseScanProperties properties;
    private final Clock clock;

    public ResponseInspector(ResponseScanClient scanClient,
                             ResponsePolicyClient policyClient,
                             ResponseScanProperties properties,
                             Clock clock) {
        this.scanClient = scanClient;
        this.policyClient = policyClient;
        this.properties = properties;
        this.clock = clock;
    }

    public boolean scans(FinancialTool tool) {
        return TEXT_TOOLS.contains(tool);
    }

    public boolean enabled() {
        return properties.enabled();
    }

    public Inspection inspect(String requestId, FinancialTool tool, String targetConsumerId, String text) {
        long started = clock.millis();
        if (text.getBytes(StandardCharsets.UTF_8).length > properties.maxDocumentBytes()) {
            return Inspection.failed("RESPONSE_TOO_LARGE", "GATEWAY");
        }
        Scan scan;
        try {
            scan = verifiedScan(scanClient.scan(requestId, tool.name(), targetConsumerId, text), text);
        } catch (ResponseScanUnavailableException | UntrustedException exception) {
            log.warn("Response scan failed requestId={} reason={}", requestId, exception.getMessage());
            return Inspection.failed("RESPONSE_SCAN_UNAVAILABLE", "AI_RISK");
        }
        ResponseDecision decision;
        try {
            decision = verifiedDecision(
                policyClient.decide(requestId, tool.name(), scan.counts(), scan.detectorVersion()), scan);
        } catch (ResponseScanUnavailableException | UntrustedException exception) {
            log.warn("Response policy failed requestId={} reason={}", requestId, exception.getMessage());
            return Inspection.failed("POLICY_ENGINE_UNAVAILABLE", "OPA");
        }
        // 단계마다 시간 제한이 있지만 전체 예산도 넘지 않아야 한다. 넘었으면 늦은 결과를 쓰지 않는다.
        if (clock.millis() - started > properties.budgetMs()) {
            log.warn("Response inspection exceeded its budget requestId={}", requestId);
            return Inspection.failed("RESPONSE_SCAN_UNAVAILABLE", "AI_RISK");
        }
        return switch (decision.decision()) {
            case ALLOW -> Inspection.released(text, scan, decision);
            case BLOCK -> Inspection.blocked(scan, decision);
            case MASK -> {
                try {
                    yield Inspection.released(masked(text, scan.findings()), scan, decision);
                } catch (UntrustedException exception) {
                    log.error("Masking check failed requestId={}", requestId);
                    yield Inspection.failed("MASKING_FAILED", "GATEWAY");
                }
            }
            case APPROVAL -> throw new IllegalStateException("verifiedDecision never returns APPROVAL");
        };
    }

    /** 탐지기 응답을 계약(docs/04 §19.2)대로만 받는다. 하나라도 어긋나면 믿지 않는다. */
    static Scan verifiedScan(JsonNode raw, String text) {
        if (raw == null || !raw.isObject() || !fieldNames(raw).equals(SCAN_FIELDS)) {
            throw new UntrustedException("scan shape");
        }
        JsonNode version = raw.get("detectorVersion");
        if (!version.isTextual() || !DETECTOR_VERSION.matcher(version.asText()).matches()) {
            throw new UntrustedException("detector version");
        }
        JsonNode rawFindings = raw.get("findings");
        if (!rawFindings.isArray() || rawFindings.size() > MAX_FINDINGS) {
            throw new UntrustedException("findings shape");
        }
        List<Finding> findings = new ArrayList<>();
        Map<String, Integer> found = new LinkedHashMap<>();
        CATEGORIES.forEach(category -> found.put(category, 0));
        int previousEnd = 0;
        for (JsonNode node : rawFindings) {
            if (!node.isObject() || !fieldNames(node).equals(FINDING_FIELDS)) {
                throw new UntrustedException("finding shape");
            }
            String category = node.get("category").isTextual() ? node.get("category").asText() : "";
            if (!CATEGORIES.contains(category)) {
                throw new UntrustedException("finding category");
            }
            int start = index(node.get("start"));
            int end = index(node.get("end"));
            if (start < previousEnd || start >= end || end > text.length()
                    || splitsSurrogatePair(text, start) || splitsSurrogatePair(text, end)) {
                throw new UntrustedException("finding span");
            }
            findings.add(new Finding(category, start, end));
            found.merge(category, 1, Integer::sum);
            previousEnd = end;
        }
        JsonNode counts = raw.get("counts");
        if (!counts.isObject() || !fieldNames(counts).equals(new HashSet<>(CATEGORIES))) {
            throw new UntrustedException("counts shape");
        }
        Map<String, Integer> declared = new LinkedHashMap<>();
        for (String category : CATEGORIES) {
            JsonNode value = counts.get(category);
            if (!value.isIntegralNumber() || !value.canConvertToInt()
                    || value.intValue() < 0 || value.intValue() > MAX_COUNT) {
                throw new UntrustedException("count value");
            }
            declared.put(category, value.intValue());
        }
        if (!declared.equals(found)) {
            throw new UntrustedException("counts disagree with findings");
        }
        return new Scan(version.asText(), List.copyOf(findings), Map.copyOf(declared));
    }

    /** OPA 응답 판정을 검증한다. 형식이 맞아도 건수와 모순되면 믿지 않는다 — 판정을 여기서 지어내지 않는다. */
    static ResponseDecision verifiedDecision(JsonNode raw, Scan scan) {
        JsonNode result = raw == null ? null : raw.get("result");
        if (result == null || !result.isObject()) {
            throw new UntrustedException("no response decision");
        }
        PolicyDecision decision = switch (text(result, "decision")) {
            case "ALLOW" -> PolicyDecision.ALLOW;
            case "MASK" -> PolicyDecision.MASK;
            case "BLOCK" -> PolicyDecision.BLOCK;
            default -> throw new UntrustedException("response decision value");
        };
        String severity = text(result, "severity");
        String policyVersion = text(result, "policyVersion");
        JsonNode riskFlagged = result.get("riskFlagged");
        JsonNode reasonNodes = result.get("reasonCodes");
        if (!SEVERITIES.contains(severity) || !POLICY_VERSION.matcher(policyVersion).matches()
                || riskFlagged == null || !riskFlagged.isBoolean() || reasonNodes == null || !reasonNodes.isArray()) {
            throw new UntrustedException("response decision fields");
        }
        List<String> reasons = new ArrayList<>();
        for (JsonNode reason : reasonNodes) {
            if (!reason.isTextual()) {
                throw new UntrustedException("reason code");
            }
            reasons.add(reason.asText());
        }
        Set<String> expectedMaskReasons = new TreeSet<>();
        for (String category : List.of("RRN", "ACCOUNT_NUMBER", "PHONE_NUMBER")) {
            if (scan.counts().get(category) > 0) {
                expectedMaskReasons.add(MASK_REASONS.get(category));
            }
        }
        boolean otherCustomer = scan.counts().get("OTHER_CUSTOMER") > 0;
        boolean consistent = switch (decision) {
            case BLOCK -> otherCustomer && reasons.equals(List.of(OTHER_CUSTOMER_REASON));
            case MASK -> !otherCustomer && !expectedMaskReasons.isEmpty()
                && reasons.size() == expectedMaskReasons.size() && new TreeSet<>(reasons).equals(expectedMaskReasons);
            case ALLOW -> !otherCustomer && expectedMaskReasons.isEmpty() && reasons.isEmpty();
            case APPROVAL -> false;
        };
        if (!consistent) {
            throw new UntrustedException("response decision contradicts the counts");
        }
        return new ResponseDecision(decision, List.copyOf(reasons), severity, riskFlagged.booleanValue(),
            policyVersion);
    }

    /**
     * 원문을 고치지 않고, 건드리지 않은 구간과 범주 표시를 이어 붙여 새로 만든다. 만든 뒤 가린 값이 결과에 남아 있으면
     * (탐지기가 같은 값의 다른 등장을 놓쳤거나 위치가 틀렸으면) 내보내지 않는다.
     */
    static String masked(String text, List<Finding> findings) {
        StringBuilder out = new StringBuilder(text.length());
        int previousEnd = 0;
        for (Finding finding : findings) {
            String label = LABELS.get(finding.category());
            if (label == null) {
                throw new UntrustedException("no label for category");
            }
            out.append(text, previousEnd, finding.start()).append(label);
            previousEnd = finding.end();
        }
        out.append(text, previousEnd, text.length());
        String result = out.toString();
        for (Finding finding : findings) {
            if (result.contains(text.substring(finding.start(), finding.end()))) {
                throw new UntrustedException("masked value still present");
            }
        }
        return result;
    }

    private static Set<String> fieldNames(JsonNode node) {
        Set<String> names = new HashSet<>();
        node.fieldNames().forEachRemaining(names::add);
        return names;
    }

    private static int index(JsonNode value) {
        if (value == null || !value.isIntegralNumber() || !value.canConvertToInt() || value.intValue() < 0) {
            throw new UntrustedException("finding index");
        }
        return value.intValue();
    }

    private static boolean splitsSurrogatePair(String text, int index) {
        return index > 0 && index < text.length()
            && Character.isHighSurrogate(text.charAt(index - 1)) && Character.isLowSurrogate(text.charAt(index));
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value != null && value.isTextual() ? value.asText() : "";
    }

    /** 탐지 결과. 위치는 이 클래스 밖으로 나가지 않는다 — 감사에는 건수와 버전만 간다. */
    public record Scan(String detectorVersion, List<Finding> findings, Map<String, Integer> counts) {

        @Override
        public String toString() {
            return "Scan[detectorVersion=" + detectorVersion + ", counts=" + counts + "]";
        }
    }

    public record Finding(String category, int start, int end) {
    }

    public record ResponseDecision(
        PolicyDecision decision, List<String> reasonCodes, String severity, boolean riskFlagged, String policyVersion) {
    }

    /** 검사 결과. 내보낼 텍스트는 이미 검사를 통과한(또는 가린) 것뿐이다. toString은 텍스트를 싣지 않는다. */
    public record Inspection(
        Kind kind, String releasedText, Scan scan, ResponseDecision decision, String reasonCode, String errorLocation) {

        public enum Kind {
            RELEASED,
            BLOCKED,
            FAILED,
        }

        static Inspection released(String text, Scan scan, ResponseDecision decision) {
            return new Inspection(Kind.RELEASED, text, scan, decision, null, null);
        }

        static Inspection blocked(Scan scan, ResponseDecision decision) {
            return new Inspection(Kind.BLOCKED, null, scan, decision, null, null);
        }

        public static Inspection failed(String reasonCode, String errorLocation) {
            return new Inspection(Kind.FAILED, null, null, null, reasonCode, errorLocation);
        }

        @Override
        public String toString() {
            return "Inspection[kind=" + kind + ", decision=" + (decision == null ? null : decision.decision())
                + ", reasonCode=" + reasonCode + "]";
        }
    }

    /** 믿을 수 없는 응답. 메시지는 고정 문구뿐이다(받은 값을 싣지 않는다). */
    static final class UntrustedException extends RuntimeException {

        private static final long serialVersionUID = 1L;

        UntrustedException(String what) {
            super(what, null, false, false);
        }
    }
}
