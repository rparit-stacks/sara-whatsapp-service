package com.sara.whatsapp.worker;

import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Tells a background worker that there is something to do, so it does not have to look on a timer. A worker sleeps in {@link #await}
 * until the code that creates its work calls {@link #wake} (a message was queued, a broadcast started) or {@link #wakeFor} (a retry
 * or a scheduled broadcast that falls due later). The sleep has a timeout only as a safety net, for work saved by another instance or
 * before a restart, so an idle service reads the database a couple of times a minute instead of several times a second.
 */
@Component
public class WorkSignal {

    public enum Kind { DISPATCH, BROADCAST, EVENTS }

    private final Map<Kind, Semaphore> signals = new EnumMap<>(Kind.class);
    private final ScheduledExecutorService timer = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "work-signal-timer");
        t.setDaemon(true);
        return t;
    });

    public WorkSignal() {
        for (Kind k : Kind.values()) {
            signals.put(k, new Semaphore(0));
        }
    }

    /** There is work now. */
    public void wake(Kind kind) {
        signals.get(kind).release();
    }

    /** There will be work at {@code dueAt}; wake then. A time that is null or already past wakes now. */
    public void wakeFor(Kind kind, Instant dueAt) {
        long ms = dueAt == null ? 0 : Duration.between(Instant.now(), dueAt).toMillis();
        if (ms <= 0) {
            wake(kind);
        } else {
            timer.schedule(() -> wake(kind), ms, TimeUnit.MILLISECONDS);
        }
    }

    /** Sleeps until woken or until {@code timeoutMs} passes, whichever comes first. A wake-up that arrived earlier is not lost. */
    public void await(Kind kind, long timeoutMs) {
        Semaphore s = signals.get(kind);
        try {
            s.tryAcquire(timeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        s.drainPermits();
    }

    @PreDestroy
    void shutdown() {
        timer.shutdownNow();
    }
}
