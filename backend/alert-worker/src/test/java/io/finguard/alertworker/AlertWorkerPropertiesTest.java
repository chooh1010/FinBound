package io.finguard.alertworker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;
import jakarta.validation.Validator;

/** 출처 값. 오타면 기동하지 않는다 — 두 출처가 모두 꺼진 채 UP으로 보이지 않게. */
class AlertWorkerPropertiesTest {

    private static final Validator VALIDATOR = Validation.buildDefaultValidatorFactory().getValidator();

    @Test
    void onlyFeedOrKafkaIsAValidSource() {
        assertThat(VALIDATOR.validate(properties("feed"))).isEmpty();
        assertThat(VALIDATOR.validate(properties("kafka"))).isEmpty();
        assertThat(VALIDATOR.validate(properties("kafak"))).isNotEmpty();
        assertThat(VALIDATOR.validate(properties(null))).isNotEmpty();
    }

    private static AlertWorkerProperties properties(String source) {
        return new AlertWorkerProperties("http://core", "credential", true, Duration.ofSeconds(1), 100,
                Duration.ofSeconds(60), 5, 3, 5, true, source);
    }
}
