package io.github.vedmider.gateway.application;

import java.util.function.LongSupplier;

/**
 * Token bucket rate limiter: a bucket of {@code capacity} tokens refilled at {@code refillPerSecond},
 * allowing short bursts while bounding the sustained rate. Thread-safe, ephemeral, injectable clock.
 * A non-positive refill rate means "unlimited" (always allows).
 */
public final class TokenBucket {

    private final double capacity;
    private final double refillPerMs;
    private final LongSupplier clockMs;
    private double tokens;
    private long last;

    public TokenBucket(double capacity, double refillPerSecond, LongSupplier clockMs) {
        this.capacity = Math.max(1.0, capacity);
        this.refillPerMs = Math.max(0.0, refillPerSecond) / 1000.0;
        this.clockMs = clockMs;
        this.tokens = this.capacity;
        this.last = clockMs.getAsLong();
    }

    public boolean unlimited() {
        return refillPerMs <= 0.0;
    }

    /** Consume one token if available; refills lazily from elapsed time first. */
    public synchronized boolean tryConsume() {
        if (unlimited()) {
            return true;
        }
        refill();
        if (tokens >= 1.0) {
            tokens -= 1.0;
            return true;
        }
        return false;
    }

    /** Milliseconds until at least one token is available (for a Retry-After hint). */
    public synchronized long millisUntilNextToken() {
        if (unlimited()) {
            return 0;
        }
        refill();
        double needed = 1.0 - tokens;
        return needed <= 0 ? 0 : (long) Math.ceil(needed / refillPerMs);
    }

    private void refill() {
        long now = clockMs.getAsLong();
        tokens = Math.min(capacity, tokens + (now - last) * refillPerMs);
        last = now;
    }
}
