package io.finguard.alertworker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.time.Duration;

import org.junit.jupiter.api.Test;

/** 다음 폴링까지의 간격. 꽉 찬 묶음이면 바로, 실패가 있으면 백오프가 우선이다. */
class AlertWorkerSchedulingTest {

    private final AlertWorker worker = mock(AlertWorker.class);

    @Test
    void fullPageIsFollowedImmediatelyWhenDrainingIsOn() {
        when(worker.lastPageFull()).thenReturn(true);

        assertThat(scheduling(true).nextDelay()).isZero();
        assertThat(scheduling(false).nextDelay()).isEqualTo(1000);
    }

    @Test
    void partialPageWaitsForTheInterval() {
        when(worker.lastPageFull()).thenReturn(false);

        assertThat(scheduling(true).nextDelay()).isEqualTo(1000);
    }

    @Test
    void failuresBackOffEvenAfterAFullPage() {
        when(worker.lastPageFull()).thenReturn(true);
        when(worker.consecutiveFailures()).thenReturn(2);

        assertThat(scheduling(true).nextDelay()).isEqualTo(4000);
    }

    private AlertWorkerScheduling scheduling(boolean drainWhenFull) {
        return new AlertWorkerScheduling(worker, new AlertWorkerProperties("http://core", "credential", true,
            Duration.ofSeconds(1), 100, Duration.ofSeconds(60), 5, 3, 5, drainWhenFull, "feed"));
    }
}
