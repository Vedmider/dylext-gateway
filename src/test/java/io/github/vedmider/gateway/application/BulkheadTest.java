package io.github.vedmider.gateway.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class BulkheadTest {

    @Test
    void capsConcurrencyAndReleaseRestoresAPermit() {
        Bulkhead b = new Bulkhead(2, 0);
        assertThat(b.tryAcquire()).isTrue();
        assertThat(b.tryAcquire()).isTrue();
        assertThat(b.tryAcquire()).isFalse();      // full
        b.release();
        assertThat(b.tryAcquire()).isTrue();        // permit returned
    }

    @Test
    void nonPositiveMaxIsUnlimited() {
        Bulkhead b = new Bulkhead(0, 0);
        assertThat(b.unlimited()).isTrue();
        for (int i = 0; i < 1000; i++) {
            assertThat(b.tryAcquire()).isTrue();
        }
        b.release();   // no-op, must not throw
    }
}
