package io.github.vedmider.gateway.application;

import io.github.vedmider.gateway.domain.model.ProviderModule;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Enumeration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Protocol-agnostic reverse proxy applying standard gateway practice: streams the raw request/response
 * (no heap buffering), filters hop-by-hop + JDK-restricted headers (RFC 7230), adds
 * X-Forwarded-For/Proto/Host and an X-Request-Id correlation id (generated if absent, echoed to the
 * client), enforces a request-size cap, and maps upstream timeout→504 / failure→502. It never parses
 * any application protocol — the body is opaque bytes. Thin and stateless; side effect is telemetry.
 */
@Service
public class GatewayProxy {

    /** Hop-by-hop + JDK-restricted headers never copied from the client request. */
    private static final Set<String> SKIP_HEADERS = Set.of("host", "content-length", "connection",
            "transfer-encoding", "keep-alive", "te", "trailer", "upgrade", "accept-encoding",
            "expect", "date", "via", "warning", "from");

    /** Forwarding headers the proxy manages itself (so inbound copies are not blindly duplicated). */
    private static final Set<String> MANAGED_HEADERS = Set.of("x-forwarded-for", "x-forwarded-proto",
            "x-forwarded-host", "x-request-id");

    static final String REQUEST_ID = "X-Request-Id";

    private final HttpClient http;
    private final RouteRegistry routes;
    private final MeterRegistry metrics;
    private final boolean telemetryEnabled;
    private final Duration upstreamTimeout;
    private final long maxRequestBytes;

    public GatewayProxy(RouteRegistry routes, MeterRegistry metrics,
                        @Value("${gateway.telemetry-enabled:true}") boolean telemetryEnabled,
                        @Value("${gateway.connect-timeout-seconds:2}") long connectTimeoutSeconds,
                        @Value("${gateway.upstream-timeout-seconds:60}") long upstreamTimeoutSeconds,
                        @Value("${gateway.max-request-bytes:0}") long maxRequestBytes) {
        this.routes = routes;
        this.metrics = metrics;
        this.telemetryEnabled = telemetryEnabled;
        this.upstreamTimeout = Duration.ofSeconds(upstreamTimeoutSeconds);
        this.maxRequestBytes = maxRequestBytes;
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)      // upstreams are plain HTTP/1.1; avoid h2c upgrade headers
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds)).build();
    }

    public void forward(HttpServletRequest request, HttpServletResponse response) throws Exception {
        ProviderModule module = routes.match(request.getRequestURI()).orElse(null);
        if (module == null) {
            response.sendError(HttpServletResponse.SC_NOT_FOUND,
                    "no provider module for path: " + request.getRequestURI());
            return;
        }
        String requestId = correlationId(request);
        response.setHeader(REQUEST_ID, requestId);     // echo so the caller can trace the request
        if (maxRequestBytes > 0 && request.getContentLengthLong() > maxRequestBytes) {
            response.sendError(HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                    "request body exceeds " + maxRequestBytes + " bytes");
            return;
        }
        long start = System.nanoTime();
        int status = HttpServletResponse.SC_BAD_GATEWAY;
        try {
            status = proxyTo(module, request, response, requestId);
        } finally {
            if (telemetryEnabled) {
                metrics.timer("gateway.proxy", "provider", module.name(), "route", module.pathPrefix(),
                                "method", request.getMethod(), "status", Integer.toString(status))
                        .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            }
        }
    }

    private int proxyTo(ProviderModule module, HttpServletRequest request, HttpServletResponse response,
                        String requestId) throws Exception {
        String query = request.getQueryString();
        URI target = URI.create(module.upstreamBaseUrl() + request.getRequestURI()
                + (query == null ? "" : "?" + query));

        HttpRequest.Builder out = HttpRequest.newBuilder(target).timeout(upstreamTimeout)
                .method(request.getMethod(), bodyPublisher(request));
        copyRequestHeaders(request, out);
        addForwardingHeaders(request, out, requestId);

        HttpResponse<InputStream> upstream;
        try {
            upstream = http.send(out.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (HttpTimeoutException timeout) {
            response.sendError(HttpServletResponse.SC_GATEWAY_TIMEOUT, module.name() + " upstream timed out");
            return HttpServletResponse.SC_GATEWAY_TIMEOUT;
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, module.name() + " proxy interrupted");
            return HttpServletResponse.SC_BAD_GATEWAY;
        } catch (Exception unreachable) {
            response.sendError(HttpServletResponse.SC_BAD_GATEWAY, module.name() + " upstream unreachable");
            return HttpServletResponse.SC_BAD_GATEWAY;
        }

        response.setStatus(upstream.statusCode());
        upstream.headers().map().forEach((k, vs) -> {
            if (!SKIP_HEADERS.contains(k.toLowerCase()) && !k.equalsIgnoreCase(REQUEST_ID)) {
                vs.forEach(v -> response.addHeader(k, v));
            }
        });
        try (InputStream in = upstream.body()) {
            in.transferTo(response.getOutputStream());
        }
        return upstream.statusCode();
    }

    private static void copyRequestHeaders(HttpServletRequest request, HttpRequest.Builder out) {
        Enumeration<String> names = request.getHeaderNames();
        while (names.hasMoreElements()) {
            String name = names.nextElement();
            String lower = name.toLowerCase();
            if (SKIP_HEADERS.contains(lower) || MANAGED_HEADERS.contains(lower)) {
                continue;
            }
            Enumeration<String> values = request.getHeaders(name);
            while (values.hasMoreElements()) {
                try {
                    out.header(name, values.nextElement());
                } catch (IllegalArgumentException restricted) {
                    // java.net.http forbids a few header names outright — drop them, keep proxying
                }
            }
        }
    }

    /** Standard X-Forwarded-* chain + correlation id so upstreams see the original caller. */
    private static void addForwardingHeaders(HttpServletRequest request, HttpRequest.Builder out,
                                             String requestId) {
        String priorFor = request.getHeader("X-Forwarded-For");
        String client = request.getRemoteAddr();
        out.header("X-Forwarded-For", priorFor == null || priorFor.isBlank() ? client : priorFor + ", " + client);
        out.header("X-Forwarded-Proto", request.getScheme());
        String host = request.getHeader("Host");
        if (host != null && !host.isBlank()) {
            out.header("X-Forwarded-Host", host);
        }
        out.header(REQUEST_ID, requestId);
    }

    private static String correlationId(HttpServletRequest request) {
        String existing = request.getHeader(REQUEST_ID);
        return existing == null || existing.isBlank() ? UUID.randomUUID().toString() : existing;
    }

    /** Stream the body straight from the servlet input unless the request has none. */
    private static HttpRequest.BodyPublisher bodyPublisher(HttpServletRequest request) {
        String method = request.getMethod();
        boolean bodyless = ("GET".equals(method) || "HEAD".equals(method))
                && request.getContentLengthLong() <= 0;
        if (bodyless) {
            return HttpRequest.BodyPublishers.noBody();
        }
        return HttpRequest.BodyPublishers.ofInputStream(() -> {
            try {
                return request.getInputStream();
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }
}
