package com.sara.whatsapp.worker;

import com.sara.whatsapp.domain.Broadcast;
import com.sara.whatsapp.service.BroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

/** Starts due scheduled broadcasts and feeds running ones into the message queue chunk by chunk. */
@Component
public class BroadcastWorker {

    private static final Logger log = LoggerFactory.getLogger(BroadcastWorker.class);
    private static final int PER_TICK = 10;

    private final BroadcastService service;
    private final WorkSignal signal;

    public BroadcastWorker(BroadcastService service, WorkSignal signal) {
        this.service = service;
        this.signal = signal;
    }

    /** Safety net sleep with nothing signalled, and the pause between rounds while a broadcast is running (it sets the pace of a send). */
    private static final long SAFETY_MS = 30_000;
    private static final long PACE_MS = 2_000;
    private volatile boolean running = true;

    @PostConstruct
    void start() {
        Thread t = new Thread(this::loop, "broadcast-worker");
        t.setDaemon(true);
        t.start();
    }

    @PreDestroy
    void stop() {
        running = false;
        signal.wake(WorkSignal.Kind.BROADCAST);
    }

    private void loop() {
        while (running) {
            try {
                boolean worked = tick();
                signal.await(WorkSignal.Kind.BROADCAST, worked ? PACE_MS : SAFETY_MS);
            } catch (Exception e) {
                log.error("Broadcast loop failed, trying again in a moment", e);
                signal.await(WorkSignal.Kind.BROADCAST, 5_000);
            }
        }
    }

    /** Starts broadcasts that are due and moves running ones along by one chunk. True when a broadcast was worked on. */
    boolean tick() {
        service.startDue();
        boolean worked = false;
        for (int i = 0; i < PER_TICK; i++) {
            Broadcast broadcast = service.claimRunning();
            if (broadcast == null) {
                break;
            }
            worked = true;
            try {
                if (!broadcast.isExpansionDone()) {
                    service.expandNext(broadcast);
                }
                service.completeIfDone(broadcast.getId());
            } catch (Exception e) {
                log.error("Broadcast {} failed this round, it will be retried", broadcast.getId(), e);
            } finally {
                service.release(broadcast.getId());
            }
        }
        return worked;
    }
}
