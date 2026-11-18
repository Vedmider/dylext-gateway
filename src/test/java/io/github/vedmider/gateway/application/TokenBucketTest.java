package io.github.vedmider.gateway.application;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class TokenBucketTest {

    @Test
    void allowsBurstThenRejectsUntilRefill() {
        AtomicLong now = new AtomicLong(0);
        TokenBucket b = new TokenBucket(2, 1, now::get);   // capacity 2, 1 token/sec
        assertThat(b.tryConsume()).isTrue();               // burst 1
        assertThat(b.tryConsume()).isTrue();               // burst 2
        assertThat(b.tryConsume()).isFalse();              // drained
        now.addAndGet(1000);                               // +1s -> +1 token
        assertThat(b.tryConsume()).isTrue();
        assertThat(b.tryConsume()).isFalse();
    }

    @Test
    void nonPositiveRateIsUnlimited() {
        TokenBucket b = new TokenBucket(1, 0, () -> 0L);
        assertThat(b.unlimited()).isTrue();
        for (int i = 0; i < 1000; i++) {
            assertThat(b.tryConsume()).isTrue();
        }
    }

    @Test
    void millisUntilNextTokenReflectsRefillRate() {
        AtomicLong now = new AtomicLong(0);
        TokenBucket b = new TokenBucket(1, 2, now::get);   // 2 tokens/sec -> 500ms each
        assertThat(b.tryConsume()).isTrue();
        assertThat(b.millisUntilNextToken()).isBetween(1L, 500L);
    }
}
