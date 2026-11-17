package io.github.vedmider.gateway.adapter.in.web;

import io.github.vedmider.gateway.application.RouteRegistry;
import io.github.vedmider.gateway.domain.model.ProviderModule;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.URI;

/**
 * Reports per-provider upstream reachability as actuator health details (a quick TCP connect), so an
 * orchestrator sees which backends the gateway can reach. It does NOT fail overall health when one
 * provider is down — the gateway still serves the others — it surfaces the state as details.
 */
@Component
public class ProvidersHealthIndicator implements HealthIndicator {

    private static final int CONNECT_TIMEOUT_MS = 500;

    private final RouteRegistry routes;

    public ProvidersHealthIndicator(RouteRegistry routes) {
        this.routes = routes;
    }

    @Override
    public Health health() {
        Health.Builder health = Health.up();
        for (ProviderModule module : routes.modules()) {
            health.withDetail(module.name(), reachable(module.upstreamBaseUrl()) ? "reachable" : "unreachable");
        }
        return health.build();
    }

    private static boolean reachable(String baseUrl) {
        try {
            URI uri = URI.create(baseUrl);
            int port = uri.getPort() > 0 ? uri.getPort() : "https".equals(uri.getScheme()) ? 443 : 80;
            try (Socket socket = new Socket()) {
                socket.connect(new InetSocketAddress(uri.getHost(), port), CONNECT_TIMEOUT_MS);
                return true;
            }
        } catch (Exception unreachable) {
            return false;
        }
    }
}
