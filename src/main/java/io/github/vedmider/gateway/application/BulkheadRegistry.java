package io.github.vedmider.gateway.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One {@link Bulkhead} per provider. max-concurrent <= 0 disables isolation (unlimited). */
@Component
public class BulkheadRegistry {

    private final int maxConcurrent;
    private final long acquireTimeoutMs;
    private final Map<String, Bulkhead> byProvider = new ConcurrentHashMap<>();

    public BulkheadRegistry(
            @Value("${gateway.bulkhead.max-concurrent:0}") int maxConcurrent,
            @Value("${gateway.bulkhead.acquire-timeout-ms:0}") long acquireTimeoutMs) {
        this.maxConcurrent = maxConcurrent;
        this.acquireTimeoutMs = acquireTimeoutMs;
    }

    public boolean enabled() {
        return maxConcurrent > 0;
    }

    public Bulkhead forProvider(String provider) {
        return byProvider.computeIfAbsent(provider, p -> new Bulkhead(maxConcurrent, acquireTimeoutMs));
    }
}
