package io.github.vedmider.gateway.application;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class CircuitBreakerTest {

    private final AtomicLong now = new AtomicLong(0);

    private CircuitBreaker breaker(int threshold, long openMs) {
        return new CircuitBreaker(threshold, openMs, now::get);
    }

    @Test
    void opensAfterThresholdConsecutiveFailuresAndFastFails() {
        CircuitBreaker cb = breaker(3, 1000);
        assertThat(cb.allow()).isTrue();
        cb.recordFailure();
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        cb.recordFailure();                       // 3rd failure trips it
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.allow()).isFalse();         // fast-fail while open
    }

    @Test
    void halfOpensAfterCooldownThenClosesOnSuccess() {
        CircuitBreaker cb = breaker(1, 1000);
        cb.recordFailure();                        // opens immediately (threshold 1)
        assertThat(cb.allow()).isFalse();
        now.set(1000);                             // cooldown elapsed
        assertThat(cb.allow()).isTrue();           // half-open trial permitted
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        cb.recordSuccess();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void halfOpenFailureReopensImmediately() {
        CircuitBreaker cb = breaker(1, 1000);
        cb.recordFailure();
        now.set(1000);
        cb.allow();                                // -> HALF_OPEN
        cb.recordFailure();                        // trial failed -> OPEN again
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.OPEN);
        assertThat(cb.allow()).isFalse();
    }

    @Test
    void successResetsTheFailureCount() {
        CircuitBreaker cb = breaker(2, 1000);
        cb.recordFailure();
        cb.recordSuccess();                        // resets
        cb.recordFailure();
        assertThat(cb.state()).isEqualTo(CircuitBreaker.State.CLOSED);   // not 2 consecutive
    }
}
