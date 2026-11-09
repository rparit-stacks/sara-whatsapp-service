package com.sara.whatsapp.worker;

import com.sara.whatsapp.domain.Broadcast;
import com.sara.whatsapp.service.BroadcastService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Starts due scheduled broadcasts and feeds running ones into the message queue chunk by chunk. */
@Component
public class BroadcastWorker {

    private static final Logger log = LoggerFactory.getLogger(BroadcastWorker.class);
    private static final int PER_TICK = 10;

    private final BroadcastService service;

    public BroadcastWorker(BroadcastService service) {
        this.service = service;
    }

    /** With nothing running or due, the database is asked again every five seconds; while a broadcast runs, about every three. */
    private static final long IDLE_MS = 5000;
    private static final long BUSY_MS = 2000;
    private volatile long pollAfter = 0;

    @Scheduled(fixedDelay = 1000)
    public void tick() {
        if (System.currentTimeMillis() < pollAfter) {
            return;
        }
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
        pollAfter = System.currentTimeMillis() + (worked ? BUSY_MS : IDLE_MS);
    }
}
