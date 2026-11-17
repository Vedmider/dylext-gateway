package io.github.vedmider.gateway.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One {@link CircuitBreaker} per provider, created on first use from the configured thresholds. */
@Component
public class CircuitBreakerRegistry {

    private final int failureThreshold;
    private final long openDurationMs;
    private final Map<String, CircuitBreaker> byProvider = new ConcurrentHashMap<>();

    public CircuitBreakerRegistry(
            @Value("${gateway.circuit-breaker.failure-threshold:5}") int failureThreshold,
            @Value("${gateway.circuit-breaker.open-seconds:10}") long openSeconds) {
        this.failureThreshold = failureThreshold;
        this.openDurationMs = openSeconds * 1000L;
    }

    public CircuitBreaker forProvider(String provider) {
        return byProvider.computeIfAbsent(provider,
                p -> new CircuitBreaker(failureThreshold, openDurationMs, System::currentTimeMillis));
    }
}
