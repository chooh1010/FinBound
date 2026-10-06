package io.finguard.core.security;

import java.security.SecureRandom;
import java.util.HexFormat;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.Ordered;

/**
 * 내부 이벤트 피드의 인증 배선. docs/04 §18.
 *
 * <p>피드 Credential은 읽기 전용 소비자용이다. 감사 행을 쓸 수 있는 내부 Credential과 분리한다: 각 Credential을 서로 다른
 * 서블릿 패턴({@code /feed/*}, {@code /internal/*})에 붙이므로 한쪽 값으로 다른 쪽 경로를 부를 수 없다. 두 값이 같으면
 * 분리가 무의미하므로 기동하지 않는다. 피드 Credential을 두지 않으면 아무도 열 수 없는 무작위 값으로 막는다.
 */
@Configuration
@EnableConfigurationProperties(EventFeedSecurityConfig.EventFeedProperties.class)
public class EventFeedSecurityConfig {

    static final String FEED_URL_PATTERN = "/feed/*";

    private static final Logger log = LoggerFactory.getLogger(EventFeedSecurityConfig.class);

    @Bean
    FilterRegistrationBean<InternalCredentialFilter> eventFeedCredentialFilterRegistration(
            EventFeedProperties feed, InternalApiProperties internal) {
        FilterRegistrationBean<InternalCredentialFilter> registration =
                new FilterRegistrationBean<>(new InternalCredentialFilter(expectedCredential(feed, internal)));
        registration.addUrlPatterns(FEED_URL_PATTERN);
        registration.setOrder(Ordered.HIGHEST_PRECEDENCE + 10);
        return registration;
    }

    static String expectedCredential(EventFeedProperties feed, InternalApiProperties internal) {
        String credential = feed.credential();
        if (credential == null || credential.isBlank()) {
            // 조용히 넘어가면 배포 설정 실수(변수 이름 오타 등)가 "피드가 전부 401"로만 드러난다.
            log.warn("Event feed credential is not configured; the feed stays closed."
                    + " Set FINGUARD_EVENT_FEED_CREDENTIAL to open it.");
            byte[] random = new byte[32];
            new SecureRandom().nextBytes(random);
            return HexFormat.of().formatHex(random);
        }
        if (credential.equals(internal.credential())) {
            throw new IllegalStateException(
                    "finguard.events.feed.credential must differ from finguard.internal.credential");
        }
        return credential;
    }

    /** @param credential 피드 전용 읽기 Credential. 비우면 피드를 열지 않는다 */
    @ConfigurationProperties(prefix = "finguard.events.feed")
    public record EventFeedProperties(String credential) {
    }
}
