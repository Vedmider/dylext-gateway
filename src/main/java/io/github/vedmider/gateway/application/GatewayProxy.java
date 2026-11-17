package io.github.vedmider.gateway.application;

import io.github.vedmider.gateway.domain.model.ProviderModule;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Protocol-agnostic reverse proxy: forwards the raw HTTP request (method, path, query, headers, body)
 * to the upstream of the owning provider module and streams the raw response back. It never parses or
 * rebuilds any application protocol (MCP/JSON-RPC, REST, …) — the body is opaque bytes. Thin and
 * stateless; the only side effect is per-call telemetry.
 */
@Service
public class GatewayProxy {

    /** Headers the proxy must not copy: hop-by-hop + headers java.net.http forbids setting. */
    private static final Set<String> SKIP_HEADERS = Set.of("host", "content-length", "connection",
            "transfer-encoding", "keep-alive", "te", "trailer", "upgrade", "accept-encoding",
            "expect", "date", "via", "warning", "from");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final RouteRegistry routes;
    private final MeterRegistry metrics;
    private final boolean telemetryEnabled;

    public GatewayProxy(RouteRegistry routes, MeterRegistry metrics,
                        @Value("${gateway.telemetry-enabled:true}") boolean telemetryEnabled) {
        this.routes = routes;
        this.metrics = metrics;
        this.telemetryEnabled = telemetryEnabled;
    }

    public void forward(HttpServletRequest request, HttpServletResponse response) throws Exception {
        String path = request.getRequestURI();
        ProviderModule module = routes.match(path).orElse(null);
        if (module == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND, "no provider module for path: " + path);
            return;
        }
        long start = System.nanoTime();
        int status = 502;
        try {
            status = proxyTo(module, request, response);
        } finally {
            if (telemetryEnabled) {
                metrics.timer("gateway.proxy", "provider", module.name(), "route", module.pathPrefix(),
                                "method", request.getMethod(), "status", Integer.toString(status))
                        .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            }
        }
    }

    private int proxyTo(ProviderModule module, HttpServletRequest request, HttpServletResponse response)
            throws Exception {
        String query = request.getQueryString();
        URI target = URI.create(module.upstreamBaseUrl() + request.getRequestURI()
                + (query == null ? "" : "?" + query));
        byte[] body = request.getInputStream().readAllBytes();

        HttpRequest.Builder out = HttpRequest.newBuilder(target).timeout(Duration.ofSeconds(60))
                .method(request.getMethod(),
                        body.length == 0 ? HttpRequest.BodyPublishers.noBody()
                                : HttpRequest.BodyPublishers.ofByteArray(body));
        var headerNames = request.getHeaderNames();
        while (headerNames.hasMoreElements()) {
            String name = headerNames.nextElement();
            if (SKIP_HEADERS.contains(name.toLowerCase())) {
                continue;
            }
            try {
                out.header(name, request.getHeader(name));
            } catch (IllegalArgumentException restricted) {
                // java.net.http forbids a few header names outright — drop them, keep proxying
            }
        }

        HttpResponse<byte[]> upstream;
        try {
            upstream = http.send(out.build(), HttpResponse.BodyHandlers.ofByteArray());
        } catch (Exception unreachable) {
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY,
                    module.name() + " upstream unreachable");
            return HttpServletResponse.SC_BAD_GATEWAY;
        }
        response.setStatus(upstream.statusCode());
        upstream.headers().map().forEach((k, vs) -> {
            if (!SKIP_HEADERS.contains(k.toLowerCase())) {
                vs.forEach(v -> response.addHeader(k, v));
            }
        });
        response.getOutputStream().write(upstream.body());
        return upstream.statusCode();
    }
}
