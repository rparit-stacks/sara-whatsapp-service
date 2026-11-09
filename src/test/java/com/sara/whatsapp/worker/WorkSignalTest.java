package com.sara.whatsapp.worker;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

/** The wake-up that replaces the timers: it must not lose a signal, must not spin, and must wake at a future time. */
class WorkSignalTest {

    private final WorkSignal signal = new WorkSignal();

    private long timeAwait(WorkSignal.Kind kind, long timeoutMs) {
        long t0 = System.nanoTime();
        signal.await(kind, timeoutMs);
        return (System.nanoTime() - t0) / 1_000_000;
    }

    @Test
    void aWakeBeforeTheWaitIsNotLost() {
        signal.wake(WorkSignal.Kind.DISPATCH);
        assertTrue(timeAwait(WorkSignal.Kind.DISPATCH, 5_000) < 500);
    }

    @Test
    void severalWakesCountAsOneSoTheWorkerDoesNotSpin() {
        signal.wake(WorkSignal.Kind.EVENTS);
        signal.wake(WorkSignal.Kind.EVENTS);
        signal.wake(WorkSignal.Kind.EVENTS);
        assertTrue(timeAwait(WorkSignal.Kind.EVENTS, 5_000) < 500);
        assertTrue(timeAwait(WorkSignal.Kind.EVENTS, 300) >= 250, "the second wait must really wait");
    }

    @Test
    void withNothingSignalledItSleepsUntilTheSafetyTimeout() {
        assertTrue(timeAwait(WorkSignal.Kind.BROADCAST, 300) >= 250);
    }

    @Test
    void aSignalForOneKindDoesNotWakeAnother() {
        signal.wake(WorkSignal.Kind.DISPATCH);
        assertTrue(timeAwait(WorkSignal.Kind.BROADCAST, 300) >= 250);
    }

    @Test
    void aFutureDueTimeWakesThenAndAPastOneWakesNow() {
        signal.wakeFor(WorkSignal.Kind.DISPATCH, Instant.now().minus(Duration.ofSeconds(5)));
        assertTrue(timeAwait(WorkSignal.Kind.DISPATCH, 5_000) < 500);

        signal.wakeFor(WorkSignal.Kind.DISPATCH, Instant.now().plusMillis(400));
        long waited = timeAwait(WorkSignal.Kind.DISPATCH, 5_000);
        assertTrue(waited >= 300 && waited < 2_000, "woke after " + waited + " ms");

        signal.wakeFor(WorkSignal.Kind.DISPATCH, null);
        assertTrue(timeAwait(WorkSignal.Kind.DISPATCH, 5_000) < 500);
    }
}
