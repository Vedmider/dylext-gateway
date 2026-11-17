package io.github.vedmider.gateway.application;

import java.util.function.LongSupplier;

/**
 * Minimal per-provider circuit breaker: trips OPEN after N consecutive failures, fast-fails for a
 * cooldown, then HALF_OPEN to trial recovery (one success closes it, one failure re-trips). Thread-safe
 * and ephemeral (in-memory only — no persistence, so the gateway stays stateless in the no-DB sense).
 */
public final class CircuitBreaker {

    public enum State { CLOSED, OPEN, HALF_OPEN }

    private final int failureThreshold;
    private final long openDurationMs;
    private final LongSupplier clock;

    private State state = State.CLOSED;
    private int consecutiveFailures;
    private long openedAt;

    public CircuitBreaker(int failureThreshold, long openDurationMs, LongSupplier clock) {
        this.failureThreshold = Math.max(1, failureThreshold);
        this.openDurationMs = openDurationMs;
        this.clock = clock;
    }

    /** Whether a call may proceed. OPEN fast-fails until the cooldown elapses, then allows a trial. */
    public synchronized boolean allow() {
        if (state == State.OPEN && clock.getAsLong() - openedAt >= openDurationMs) {
            state = State.HALF_OPEN;
        }
        return state != State.OPEN;
    }

    public synchronized void recordSuccess() {
        consecutiveFailures = 0;
        state = State.CLOSED;
    }

    public synchronized void recordFailure() {
        if (state == State.HALF_OPEN || ++consecutiveFailures >= failureThreshold) {
            state = State.OPEN;
            openedAt = clock.getAsLong();
            consecutiveFailures = failureThreshold;
        }
    }

    public synchronized State state() {
        return state;
    }
}
