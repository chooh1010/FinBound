package io.finguard.core.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.finguard.core.security.EventFeedSecurityConfig.EventFeedProperties;

class EventFeedSecurityConfigTest {

    private static final InternalApiProperties INTERNAL = new InternalApiProperties("internal-secret");

    @Test
    void refusesToStartWhenTheFeedCredentialIsTheInternalOne() {
        // 같으면 읽기 전용 소비자가 감사 행을 쓸 수 있는 값을 쥐게 된다.
        assertThatThrownBy(() -> EventFeedSecurityConfig.expectedCredential(
                        new EventFeedProperties("internal-secret"), INTERNAL))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void keepsTheFeedClosedWhenNoCredentialIsConfigured() {
        String first = EventFeedSecurityConfig.expectedCredential(new EventFeedProperties(" "), INTERNAL);
        String second = EventFeedSecurityConfig.expectedCredential(new EventFeedProperties(null), INTERNAL);

        // 아무도 알 수 없는 무작위 값이다. 비어 있는 값을 기대값으로 두지 않는다.
        assertThat(first).hasSize(64).isNotEqualTo(second);
    }

    @Test
    void usesAConfiguredSeparateCredential() {
        assertThat(EventFeedSecurityConfig.expectedCredential(new EventFeedProperties("feed-secret"), INTERNAL))
                .isEqualTo("feed-secret");
    }
}
