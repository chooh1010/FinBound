package io.finguard.core.event;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * 시퀀서를 전용 스레드에서 돌린다. 다른 배치(조정·만료)와 스케줄러를 나누면, 그쪽이 오래 걸려도 피드가 멈추지 않는다.
 */
@Component
@ConditionalOnProperty(prefix = "finguard.events.sequencer", name = "enabled", havingValue = "true",
        matchIfMissing = true)
class EventSequencerScheduling implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(EventSequencerScheduling.class);
    private static final long SHUTDOWN_WAIT_SECONDS = 5;

    private final EventSequencer sequencer;
    private final EventSequencerProperties properties;
    private final Counter failures;
    private volatile ScheduledExecutorService executor;

    EventSequencerScheduling(
            EventSequencer sequencer, EventSequencerProperties properties, MeterRegistry registry) {
        this.sequencer = sequencer;
        this.properties = properties;
        this.failures = Counter.builder("events.sequencer.failures")
                .description("Sequencer runs that failed (retried next run)")
                .register(registry);
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "event-sequencer");
            thread.setDaemon(true);
            return thread;
        });
        long delay = properties.interval().toMillis();
        executor.scheduleWithFixedDelay(this::run, delay, delay, TimeUnit.MILLISECONDS);
    }

    private void run() {
        try {
            sequencer.sequenceOnce();
        } catch (RuntimeException exception) {
            failures.increment();
            log.error("Event sequencer run failed", exception);
        }
    }

    @Override
    public synchronized void stop() {
        ScheduledExecutorService running = executor;
        executor = null;
        if (running == null) {
            return;
        }
        running.shutdownNow();
        try {
            // 진행 중인 주기가 끝나기를 잠깐 기다린다. 데이터소스가 닫힌 뒤에 트랜잭션이 이어지지 않게.
            if (!running.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Event sequencer did not stop within {}s", SHUTDOWN_WAIT_SECONDS);
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public boolean isRunning() {
        return executor != null;
    }
}
