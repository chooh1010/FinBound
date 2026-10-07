package io.finguard.alertworker;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.SmartLifecycle;
import org.springframework.stereotype.Component;

/**
 * 피드를 전용 스레드에서 주기적으로 읽는다. 한 주기가 실패해도 다음 주기를 막지 않는다. 실패가 이어지면 간격을 두 배씩
 * 늘리되 30초를 넘기지 않는다 — 피드가 내려가 있는 동안 두드리기만 하지 않게.
 */
@Component
@ConditionalOnProperty(prefix = "finguard.alert-worker", name = "polling-enabled", havingValue = "true",
        matchIfMissing = true)
class AlertWorkerScheduling implements SmartLifecycle {

    private static final Logger log = LoggerFactory.getLogger(AlertWorkerScheduling.class);
    private static final long SHUTDOWN_WAIT_SECONDS = 5;
    static final long MAX_BACKOFF_MILLIS = 30_000;

    private final AlertWorker worker;
    private final AlertWorkerProperties properties;
    private volatile ScheduledExecutorService executor;

    AlertWorkerScheduling(AlertWorker worker, AlertWorkerProperties properties) {
        this.worker = worker;
        this.properties = properties;
    }

    @Override
    public synchronized void start() {
        if (executor != null) {
            return;
        }
        executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "alert-worker-poll");
            thread.setDaemon(true);
            return thread;
        });
        executor.schedule(this::run, properties.pollInterval().toMillis(), TimeUnit.MILLISECONDS);
    }

    private void run() {
        try {
            worker.runOnce();
        } catch (RuntimeException exception) {
            log.error("Alert worker run failed", exception);
        }
        ScheduledExecutorService running = executor;
        if (running != null && !running.isShutdown()) {
            running.schedule(this::run, nextDelay(), TimeUnit.MILLISECONDS);
        }
    }

    /** 꽉 찬 묶음을 처리했으면 바로(drain-when-full), 아니면 기본 간격 또는 실패 뒤 백오프. */
    long nextDelay() {
        if (properties.drainWhenFull() && worker.lastPageFull() && worker.consecutiveFailures() == 0) {
            return 0;
        }
        return delayAfter(worker.consecutiveFailures());
    }

    /** 실패가 없으면 기본 간격, 있으면 두 배씩 늘린 간격(최대 30초). */
    long delayAfter(int failures) {
        long base = properties.pollInterval().toMillis();
        if (failures <= 0) {
            return base;
        }
        long delay = base;
        for (int i = 0; i < failures && delay < MAX_BACKOFF_MILLIS; i++) {
            delay *= 2;
        }
        return Math.min(delay, MAX_BACKOFF_MILLIS);
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
            if (!running.awaitTermination(SHUTDOWN_WAIT_SECONDS, TimeUnit.SECONDS)) {
                log.warn("Alert worker did not stop within {}s", SHUTDOWN_WAIT_SECONDS);
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
