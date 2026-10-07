package io.finguard.gateway.metrics;

import java.util.function.Supplier;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

/**
 * Tool Call 처리 단계별 소요 시간({@code gateway.phase}, 태그 {@code phase}). 호출마다 행동 이력을 다시 조회하는 비용이
 * 전체에서 얼마인지 재려고 둔다(로드맵 6단계: 바꾸기 전에 먼저 잰다).
 *
 * <p>실패한 단계도 잰다 — 실패까지 걸린 시간도 그 단계의 비용이다. 태그는 단계 이름뿐이다(요청·Agent·고객 값을 싣지 않는다).
 */
@Component
public class PhaseTimer {

    public static final String METER = "gateway.phase";

    private final MeterRegistry registry;
    private final java.util.concurrent.ConcurrentMap<String, Timer> timers =
        new java.util.concurrent.ConcurrentHashMap<>();

    public PhaseTimer(MeterRegistry registry) {
        this.registry = registry;
    }

    /** 지표를 남기지 않는 타이머. 단위 테스트가 쓴다. */
    public static PhaseTimer noop() {
        return new PhaseTimer(new SimpleMeterRegistry());
    }

    public <T> T time(String phase, Supplier<T> work) {
        long started = System.nanoTime();
        try {
            return work.get();
        } finally {
            // 끝난 시각을 먼저 잰다. 지표 조회 비용이 단계 시간에 섞이지 않게.
            long elapsed = System.nanoTime() - started;
            timer(phase).record(elapsed, java.util.concurrent.TimeUnit.NANOSECONDS);
        }
    }

    public void time(String phase, Runnable work) {
        time(phase, () -> {
            work.run();
            return null;
        });
    }

    private Timer timer(String phase) {
        return timers.computeIfAbsent(phase, name -> Timer.builder(METER)
            .description("Time spent in one phase of a gateway tool call")
            .tag("phase", name)
            .publishPercentiles(0.5, 0.95)
            .register(registry));
    }
}
