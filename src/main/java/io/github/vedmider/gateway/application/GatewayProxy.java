package io.github.vedmider.gateway.application;

import io.github.vedmider.gateway.domain.model.ProviderModule;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.Arrays;
import java.util.Enumeration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * Protocol-agnostic reverse proxy with standard gateway resilience/observability:
 * streams bodies (no heap buffering), filters hop-by-hop/restricted headers, adds X-Forwarded-*,
 * X-Request-Id (per hop) and X-Trace-Id (per trace, honouring inbound traceparent), enforces a
 * request-size cap, retries only idempotent+bodyless methods on transport/gateway failures, trips a
 * per-provider circuit breaker on a flapping upstream, and emits one structured access-log line per
 * call. Never parses any application protocol — the body is opaque bytes.
 */
@Service
public class GatewayProxy {

    private static final Logger access = LoggerFactory.getLogger("gateway.access");

    private static final Set<String> SKIP_HEADERS = Set.of("host", "content-length", "connection",
            "transfer-encoding", "keep-alive", "te", "trailer", "upgrade", "accept-encoding",
            "expect", "date", "via", "warning", "from");
    private static final Set<String> MANAGED_HEADERS = Set.of("x-forwarded-for", "x-forwarded-proto",
            "x-forwarded-host", "x-request-id", "x-trace-id");
    private static final Set<Integer> RETRYABLE_STATUS = Set.of(502, 503, 504);

    static final String REQUEST_ID = "X-Request-Id";
    static final String TRACE_ID = "X-Trace-Id";

    private final HttpClient http;
    private final RouteRegistry routes;
    private final MeterRegistry metrics;
    private final CircuitBreakerRegistry breakers;
    private final RateLimiterRegistry rateLimiters;
    private final BulkheadRegistry bulkheads;
    private final boolean telemetryEnabled;
    private final Duration upstreamTimeout;
    private final long maxRequestBytes;
    private final int retryMaxAttempts;
    private final long retryBackoffMs;
    private final long retryBackoffCapMs;
    private final boolean retryJitter;
    private final Set<String> idempotentMethods;

    public GatewayProxy(RouteRegistry routes, MeterRegistry metrics, CircuitBreakerRegistry breakers,
                        RateLimiterRegistry rateLimiters, BulkheadRegistry bulkheads,
                        @Value("${gateway.telemetry-enabled:true}") boolean telemetryEnabled,
                        @Value("${gateway.connect-timeout-seconds:2}") long connectTimeoutSeconds,
                        @Value("${gateway.upstream-timeout-seconds:60}") long upstreamTimeoutSeconds,
                        @Value("${gateway.max-request-bytes:0}") long maxRequestBytes,
                        @Value("${gateway.retry.max-attempts:2}") int retryMaxAttempts,
                        @Value("${gateway.retry.backoff-ms:100}") long retryBackoffMs,
                        @Value("${gateway.retry.backoff-cap-ms:2000}") long retryBackoffCapMs,
                        @Value("${gateway.retry.jitter:true}") boolean retryJitter,
                        @Value("${gateway.retry.idempotent-methods:GET,HEAD,OPTIONS}") String idempotentMethods) {
        this.routes = routes;
        this.metrics = metrics;
        this.breakers = breakers;
        this.rateLimiters = rateLimiters;
        this.bulkheads = bulkheads;
        this.telemetryEnabled = telemetryEnabled;
        this.upstreamTimeout = Duration.ofSeconds(upstreamTimeoutSeconds);
        this.maxRequestBytes = maxRequestBytes;
        this.retryMaxAttempts = Math.max(1, retryMaxAttempts);
        this.retryBackoffMs = retryBackoffMs;
        this.retryBackoffCapMs = retryBackoffCapMs <= 0 ? retryBackoffMs : retryBackoffCapMs;
        this.retryJitter = retryJitter;
        this.idempotentMethods = Arrays.stream(idempotentMethods.split(","))
                .map(String::trim).map(String::toUpperCase).filter(s -> !s.isEmpty())
                .collect(Collectors.toUnmodifiableSet());
        this.http = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(Duration.ofSeconds(connectTimeoutSeconds)).build();
    }

    public void forward(HttpServletRequest request, HttpServletResponse response) throws Exception {
        long start = System.nanoTime();
        String requestId = correlationId(request);
        String traceId = traceId(request);
        MDC.put("requestId", requestId);
        MDC.put("traceId", traceId);
        response.setHeader(REQUEST_ID, requestId);
        response.setHeader(TRACE_ID, traceId);

        ProviderModule module = routes.match(request.getRequestURI()).orElse(null);
        String provider = module == null ? "none" : module.name();
        int status = HttpServletResponse.SC_INTERNAL_SERVER_ERROR;
        try {
            if (module == null) {
                response.sendError(status = HttpServletResponse.SC_NOT_FOUND,
                        "no provider module for path: " + request.getRequestURI());
            } else if (maxRequestBytes > 0 && request.getContentLengthLong() > maxRequestBytes) {
                response.sendError(status = HttpServletResponse.SC_REQUEST_ENTITY_TOO_LARGE,
                        "request body exceeds " + maxRequestBytes + " bytes");
            } else {
                status = proxyTo(module, request, response, requestId, traceId);
            }
        } finally {
            long ms = (System.nanoTime() - start) / 1_000_000;
            if (telemetryEnabled) {
                metrics.timer("gateway.proxy", "provider", provider, "method", request.getMethod(),
                        "status", Integer.toString(status)).record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            }
            access.info("method={} path={} provider={} status={} durationMs={} requestId={} traceId={} client={}",
                    request.getMethod(), request.getRequestURI(), provider, status, ms, requestId, traceId,
                    request.getRemoteAddr());
            MDC.remove("requestId");
            MDC.remove("traceId");
        }
    }

    private int proxyTo(ProviderModule module, HttpServletRequest request, HttpServletResponse response,
                        String requestId, String traceId) throws Exception {
        TokenBucket limiter = rateLimiters.forProvider(module.name());
        if (!limiter.tryConsume()) {
            long retryAfterMs = limiter.millisUntilNextToken();
            response.setHeader("Retry-After", Long.toString(Math.max(1, (retryAfterMs + 999) / 1000)));
            if (telemetryEnabled) {
                metrics.counter("gateway.rate_limit.rejected", "provider", module.name()).increment();
            }
            response.sendError(429, module.name() + " rate limit exceeded");
            return 429;
        }
        CircuitBreaker breaker = breakers.forProvider(module.name());
        if (!breaker.allow()) {
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                    module.name() + " circuit open");
            return HttpServletResponse.SC_SERVICE_UNAVAILABLE;
        }
        Bulkhead bulkhead = bulkheads.forProvider(module.name());
        if (!bulkhead.tryAcquire()) {
            if (telemetryEnabled) {
                metrics.counter("gateway.bulkhead.rejected", "provider", module.name()).increment();
            }
            response.sendError(HttpServletResponse.SC_SERVICE_UNAVAILABLE, module.name() + " at capacity");
            return HttpServletResponse.SC_SERVICE_UNAVAILABLE;
        }
        try {
            return send(module, request, response, requestId, traceId, breaker);
        } finally {
            bulkhead.release();
        }
    }

    private int send(ProviderModule module, HttpServletRequest request, HttpServletResponse response,
                     String requestId, String traceId, CircuitBreaker breaker) throws Exception {
        String query = request.getQueryString();
        URI target = URI.create(module.upstreamBaseUrl() + request.getRequestURI()
                + (query == null ? "" : "?" + query));
        boolean replayable = idempotentMethods.contains(request.getMethod()) && isBodyless(request);
        int attempts = replayable ? retryMaxAttempts : 1;

        for (int attempt = 1; attempt <= attempts; attempt++) {
            HttpRequest outbound = buildRequest(target, request, requestId, traceId);
            HttpResponse<InputStream> upstream;
            try {
                upstream = http.send(outbound, HttpResponse.BodyHandlers.ofInputStream());
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                breaker.recordFailure();
                response.sendError(HttpServletResponse.SC_BAD_GATEWAY, module.name() + " proxy interrupted");
                return HttpServletResponse.SC_BAD_GATEWAY;
            } catch (IOException transportFailure) {
                breaker.recordFailure();
                if (attempt < attempts && backoff(attempt)) {
                    continue;
                }
                int code = transportFailure instanceof HttpTimeoutException
                        ? HttpServletResponse.SC_GATEWAY_TIMEOUT : HttpServletResponse.SC_BAD_GATEWAY;
                response.sendError(code, module.name() + " upstream unreachable");
                return code;
            }

            int statusCode = upstream.statusCode();
            if (RETRYABLE_STATUS.contains(statusCode)) {
                breaker.recordFailure();
                if (attempt < attempts && backoff(attempt)) {
                    drain(upstream);
                    continue;
                }
            } else {
                breaker.recordSuccess();
            }
            writeResponse(response, upstream);
            return statusCode;
        }
        response.sendError(HttpServletResponse.SC_BAD_GATEWAY, module.name() + " proxy exhausted retries");
        return HttpServletResponse.SC_BAD_GATEWAY;
    }

    private HttpRequest buildRequest(URI target, HttpServletRequest request, String requestId, String traceId) {
        HttpRequest.Builder out = HttpRequest.newBuilder(target).timeout(upstreamTimeout)
                .method(request.getMethod(), bodyPublisher(request));
        copyRequestHeaders(request, out);
        addForwardingHeaders(request, out, requestId, traceId);
        return out.build();
    }

    private static void writeResponse(HttpServletResponse response, HttpResponse<InputStream> upstream)
            throws IOException {
        response.setStatus(upstream.statusCode());
        upstream.headers().map().forEach((k, vs) -> {
            if (!SKIP_HEADERS.contains(k.toLowerCase()) && !MANAGED_HEADERS.contains(k.toLowerCase())) {
                vs.forEach(v -> response.addHeader(k, v));
            }
        });
        try (InputStream in = upstream.body()) {
            in.transferTo(response.getOutputStream());
        }
    }

    private static void drain(HttpResponse<InputStream> upstream) {
        try (InputStream in = upstream.body()) {
            in.transferTo(OutputStream.nullOutputStream());
        } catch (IOException ignored) {
            // releasing the retried response's connection; nothing to do on failure
        }
    }

    private boolean backoff(int attempt) {
        try {
            Thread.sleep(computeBackoffMs(attempt, java.util.concurrent.ThreadLocalRandom.current().nextDouble()));
            return true;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return false;
        }
    }

    /** Exponential backoff (base*2^(attempt-1)) capped, with full jitter in [0, delay]. Package-private for tests. */
    long computeBackoffMs(int attempt, double rand01) {
        long exp = (long) Math.min((double) retryBackoffCapMs, retryBackoffMs * Math.pow(2, attempt - 1));
        return retryJitter ? (long) (rand01 * exp) : exp;
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

    private static void addForwardingHeaders(HttpServletRequest request, HttpRequest.Builder out,
                                             String requestId, String traceId) {
        String priorFor = request.getHeader("X-Forwarded-For");
        String client = request.getRemoteAddr();
        out.header("X-Forwarded-For", priorFor == null || priorFor.isBlank() ? client : priorFor + ", " + client);
        out.header("X-Forwarded-Proto", request.getScheme());
        String host = request.getHeader("Host");
        if (host != null && !host.isBlank()) {
            out.header("X-Forwarded-Host", host);
        }
        out.header(REQUEST_ID, requestId);
        out.header(TRACE_ID, traceId);
    }

    private static boolean isBodyless(HttpServletRequest request) {
        return request.getContentLengthLong() <= 0;
    }

    private static HttpRequest.BodyPublisher bodyPublisher(HttpServletRequest request) {
        String method = request.getMethod();
        if (("GET".equals(method) || "HEAD".equals(method)) && isBodyless(request)) {
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

    private static String correlationId(HttpServletRequest request) {
        String existing = request.getHeader(REQUEST_ID);
        return existing == null || existing.isBlank() ? UUID.randomUUID().toString() : existing;
    }

    /** W3C traceparent trace-id if present, else inbound X-Trace-Id, else a fresh 32-hex id. */
    private static String traceId(HttpServletRequest request) {
        String traceparent = request.getHeader("traceparent");
        if (traceparent != null) {
            String[] parts = traceparent.split("-");
            if (parts.length >= 2 && parts[1].length() == 32) {
                return parts[1];
            }
        }
        String header = request.getHeader(TRACE_ID);
        return header == null || header.isBlank() ? UUID.randomUUID().toString().replace("-", "") : header;
    }
}
