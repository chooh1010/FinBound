package io.finguard.core.audit;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Set;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.fasterxml.jackson.databind.ObjectMapper;

import jakarta.validation.ConstraintViolation;
import jakarta.validation.Validation;
import jakarta.validation.Validator;

/**
 * 결과 요청의 검사 증거 모양(docs/04 §19.1). Jackson은 {@code 1.5}를 정수 칸에 {@code 1}로 넣고 모르는 키를 버리므로, 이
 * 칸은 그대로 받아 직접 확인한다.
 */
class ResponseScanRequestTest {

    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"RRN\":1,\"ACCOUNT_NUMBER\":0,\"PHONE_NUMBER\":2,\"OTHER_CUSTOMER\":0}",
        "{\"RRN\":256,\"ACCOUNT_NUMBER\":0,\"PHONE_NUMBER\":0,\"OTHER_CUSTOMER\":0}"
    })
    void fourIntegerCategoriesAreAccepted(String counts) throws Exception {
        assertThat(violations("response-scan-1", "response-policy-1", counts)).isEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "{\"RRN\":1.5,\"ACCOUNT_NUMBER\":0,\"PHONE_NUMBER\":0,\"OTHER_CUSTOMER\":0}",
        "{\"RRN\":\"1\",\"ACCOUNT_NUMBER\":0,\"PHONE_NUMBER\":0,\"OTHER_CUSTOMER\":0}",
        "{\"RRN\":-1,\"ACCOUNT_NUMBER\":0,\"PHONE_NUMBER\":0,\"OTHER_CUSTOMER\":0}",
        "{\"RRN\":257,\"ACCOUNT_NUMBER\":0,\"PHONE_NUMBER\":0,\"OTHER_CUSTOMER\":0}",
        "{\"RRN\":null,\"ACCOUNT_NUMBER\":0,\"PHONE_NUMBER\":0,\"OTHER_CUSTOMER\":0}",
        "{\"RRN\":1,\"ACCOUNT_NUMBER\":0,\"PHONE_NUMBER\":0}",
        "{\"RRN\":1,\"ACCOUNT_NUMBER\":0,\"PHONE_NUMBER\":0,\"OTHER_CUSTOMER\":0,\"EMAIL\":1}",
        "[1, 0, 0, 0]"
    })
    void anythingElseIsRejected(String counts) throws Exception {
        assertThat(violations("response-scan-1", "response-policy-1", counts)).isNotEmpty();
    }

    @ParameterizedTest
    @ValueSource(strings = {"response-scan-1 900101-1234567", "", "RESPONSE-SCAN-1", "response-scan-12345"})
    void detectorVersionIsOnlyTheAllowedShape(String version) throws Exception {
        assertThat(violations(version, "response-policy-1",
                "{\"RRN\":0,\"ACCOUNT_NUMBER\":0,\"PHONE_NUMBER\":0,\"OTHER_CUSTOMER\":0}")).isNotEmpty();
    }

    private static Set<ConstraintViolation<ResponseScanRequest>> violations(
            String detectorVersion, String policyVersion, String counts) throws Exception {
        return VALIDATOR.validate(new ResponseScanRequest(detectorVersion, policyVersion, JSON.readTree(counts)));
    }
}
