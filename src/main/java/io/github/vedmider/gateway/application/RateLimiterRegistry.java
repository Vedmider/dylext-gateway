package io.github.vedmider.gateway.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** One {@link TokenBucket} per provider. requests-per-second <= 0 disables limiting (always allows). */
@Component
public class RateLimiterRegistry {

    private final double requestsPerSecond;
    private final double burst;
    private final Map<String, TokenBucket> byProvider = new ConcurrentHashMap<>();

    public RateLimiterRegistry(
            @Value("${gateway.rate-limit.requests-per-second:0}") double requestsPerSecond,
            @Value("${gateway.rate-limit.burst:0}") double burst) {
        this.requestsPerSecond = requestsPerSecond;
        this.burst = burst > 0 ? burst : Math.max(1.0, requestsPerSecond);
    }

    public boolean enabled() {
        return requestsPerSecond > 0;
    }

    public TokenBucket forProvider(String provider) {
        return byProvider.computeIfAbsent(provider,
                p -> new TokenBucket(burst, requestsPerSecond, System::currentTimeMillis));
    }
}
