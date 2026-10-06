package io.finguard.alertworker;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

/** 경보 워커. Core 내부 이벤트 피드를 읽어 경보 규칙에 반영한다(docs/04 §18). */
@SpringBootApplication
@EnableConfigurationProperties(AlertWorkerProperties.class)
public class AlertWorkerApplication {

    public static void main(String[] args) {
        SpringApplication.run(AlertWorkerApplication.class, args);
    }
}
