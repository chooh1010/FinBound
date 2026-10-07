package io.finguard.gateway.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

class PhaseTimerTest {

    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final PhaseTimer phases = new PhaseTimer(registry);

    @Test
    void recordsEachPhaseUnderItsOwnTag() {
        assertThat(phases.time("history", () -> 42)).isEqualTo(42);
        phases.time("policy", () -> { });

        assertThat(registry.get(PhaseTimer.METER).tag("phase", "history").timer().count()).isEqualTo(1);
        assertThat(registry.get(PhaseTimer.METER).tag("phase", "policy").timer().count()).isEqualTo(1);
    }

    /** 실패까지 걸린 시간도 그 단계의 비용이다. */
    @Test
    void recordsAPhaseThatFails() {
        assertThatThrownBy(() -> phases.time("history", () -> {
            throw new IllegalStateException("down");
        })).isInstanceOf(IllegalStateException.class);

        assertThat(registry.get(PhaseTimer.METER).tag("phase", "history").timer().count()).isEqualTo(1);
    }

    @Test
    void recordsTheTimeTheWorkTook() {
        phases.time("history", () -> {
            try {
                Thread.sleep(20);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });

        assertThat(registry.get(PhaseTimer.METER).tag("phase", "history").timer()
            .totalTime(java.util.concurrent.TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(19.0);
    }
}
