package io.github.vedmider.gateway.application;

import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;

/**
 * Per-provider concurrency isolation: at most {@code maxConcurrent} in-flight upstream calls, so a slow
 * provider cannot consume all gateway capacity and starve a fast one. {@code maxConcurrent <= 0} means
 * unlimited (no isolation). {@link #tryAcquire()} waits up to {@code acquireTimeoutMs} then fast-rejects.
 */
public final class Bulkhead {

    private final Semaphore permits;   // null = unlimited
    private final long acquireTimeoutMs;

    public Bulkhead(int maxConcurrent, long acquireTimeoutMs) {
        this.permits = maxConcurrent > 0 ? new Semaphore(maxConcurrent) : null;
        this.acquireTimeoutMs = Math.max(0, acquireTimeoutMs);
    }

    public boolean unlimited() {
        return permits == null;
    }

    public boolean tryAcquire() {
        if (permits == null) {
            return true;
        }
        try {
            return permits.tryAcquire(acquireTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    public void release() {
        if (permits != null) {
            permits.release();
        }
    }

    public int availablePermits() {
        return permits == null ? Integer.MAX_VALUE : permits.availablePermits();
    }
}
