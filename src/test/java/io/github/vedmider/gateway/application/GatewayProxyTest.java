package io.github.vedmider.gateway.application;

import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayProxyTest {

    private HttpServer upstream;
    private int port;
    private final AtomicInteger calls = new AtomicInteger();
    private volatile int firstCallStatus = 200;
    private volatile String seenMethod;
    private volatile String seenUri;
    private volatile String seenBody;
    private final Map<String, List<String>> seenHeaders = new ConcurrentHashMap<>();

    @BeforeEach
    void startUpstream() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/api", exchange -> {
            int n = calls.incrementAndGet();
            seenMethod = exchange.getRequestMethod();
            seenUri = exchange.getRequestURI().toString();
            try (InputStream in = exchange.getRequestBody()) {
                seenBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            exchange.getRequestHeaders().forEach(seenHeaders::put);
            int status = n == 1 ? firstCallStatus : 200;
            byte[] body = "upstream-body".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Up", "yes");
            exchange.sendResponseHeaders(status, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
        port = upstream.getAddress().getPort();
    }

    @AfterEach
    void stopUpstream() {
        upstream.stop(0);
    }

    private GatewayProxy proxy(String sestaconUrl, long maxBytes) {
        return proxy(sestaconUrl, maxBytes, 1, new CircuitBreakerRegistry(5, 10));
    }

    private GatewayProxy proxy(String sestaconUrl, long maxBytes, int retryAttempts,
                               CircuitBreakerRegistry breakers) {
        return proxy(sestaconUrl, maxBytes, retryAttempts, breakers,
                new RateLimiterRegistry(0, 0), new BulkheadRegistry(0, 0));
    }

    private GatewayProxy proxy(String sestaconUrl, long maxBytes, int retryAttempts,
                               CircuitBreakerRegistry breakers, RateLimiterRegistry limiters,
                               BulkheadRegistry bulkheads) {
        RouteRegistry routes = new RouteRegistry("http://127.0.0.1:1", sestaconUrl);
        return new GatewayProxy(routes, new SimpleMeterRegistry(), breakers, limiters, bulkheads,
                false, 2, 60, maxBytes, retryAttempts, 1, 2000, true, "GET,HEAD,OPTIONS");
    }

    private MockHttpServletResponse run(GatewayProxy proxy, MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        proxy.forward(req, res);
        return res;
    }

    private String url() {
        return "http://127.0.0.1:" + port;
    }

    @Test
    void forwardsMethodPathQueryBodyAndReturnsUpstreamStatusBodyHeaders() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/echo");
        req.setQueryString("x=1");
        req.setContent("hello".getBytes(StandardCharsets.UTF_8));
        req.setRemoteAddr("1.2.3.4");
        req.addHeader("X-Roles", "admin");
        req.addHeader("Connection", "keep-alive");

        MockHttpServletResponse res = run(proxy(url(), 0), req);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentAsString()).isEqualTo("upstream-body");
        assertThat(res.getHeader("X-Up")).isEqualTo("yes");
        assertThat(seenMethod).isEqualTo("POST");
        assertThat(seenUri).isEqualTo("/api/echo?x=1");
        assertThat(seenBody).isEqualTo("hello");
        assertThat(seenHeaders).containsKey("X-roles");
        assertThat(seenHeaders.getOrDefault("Connection", List.of())).doesNotContain("keep-alive");
    }

    @Test
    void backoffIsCappedExponentialWithFullJitter() {
        GatewayProxy p = proxy(url(), 0);                       // base=1ms, cap=2000ms, jitter=true
        assertThat(p.computeBackoffMs(1, 1.0)).isEqualTo(1);    // base
        assertThat(p.computeBackoffMs(3, 1.0)).isEqualTo(4);    // base * 2^(3-1)
        assertThat(p.computeBackoffMs(3, 0.0)).isEqualTo(0);    // full-jitter floor
        assertThat(p.computeBackoffMs(20, 1.0)).isEqualTo(2000); // capped
    }

    @Test
    void rateLimitExceededReturns429WithRetryAfter() throws Exception {
        RateLimiterRegistry limiters = new RateLimiterRegistry(0.001, 1);   // burst 1, ~never refills
        GatewayProxy p = proxy(url(), 0, 1, new CircuitBreakerRegistry(5, 10), limiters, new BulkheadRegistry(0, 0));
        MockHttpServletRequest r1 = new MockHttpServletRequest("GET", "/api/echo");
        MockHttpServletRequest r2 = new MockHttpServletRequest("GET", "/api/echo");

        assertThat(run(p, r1).getStatus()).isEqualTo(200);         // first consumes the only token
        MockHttpServletResponse res2 = run(p, r2);
        assertThat(res2.getStatus()).isEqualTo(429);               // second is rate-limited
        assertThat(res2.getHeader("Retry-After")).isNotNull();
    }

    @Test
    void bulkheadFullReturns503() throws Exception {
        BulkheadRegistry bulkheads = new BulkheadRegistry(1, 0);    // one permit, fast-reject
        bulkheads.forProvider("sestacon").tryAcquire();            // occupy the only permit
        GatewayProxy p = proxy(url(), 0, 1, new CircuitBreakerRegistry(5, 10), new RateLimiterRegistry(0, 0), bulkheads);

        MockHttpServletResponse res = run(p, new MockHttpServletRequest("GET", "/api/echo"));
        assertThat(res.getStatus()).isEqualTo(503);                // provider at capacity
    }

    @Test
    void addsForwardingHeadersRequestIdAndTraceId() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/echo");
        req.setContent("x".getBytes(StandardCharsets.UTF_8));
        req.setRemoteAddr("1.2.3.4");
        req.addHeader("Host", "gw:18070");

        MockHttpServletResponse res = run(proxy(url(), 0), req);

        assertThat(seenHeaders.get("X-forwarded-for")).containsExactly("1.2.3.4");
        assertThat(seenHeaders.get("X-forwarded-proto")).containsExactly("http");
        assertThat(seenHeaders.get("X-forwarded-host")).containsExactly("gw:18070");
        assertThat(seenHeaders.get("X-request-id")).hasSize(1);
        assertThat(seenHeaders.get("X-trace-id")).hasSize(1);
        assertThat(res.getHeader("X-Request-Id")).isNotBlank();
        assertThat(res.getHeader("X-Trace-Id")).isNotBlank();
    }

    @Test
    void honoursInboundW3cTraceparentTraceId() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/x");
        req.addHeader("traceparent", "00-0af7651916cd43dd8448eb211c80319c-b7ad6b7169203331-01");

        MockHttpServletResponse res = run(proxy(url(), 0), req);

        assertThat(res.getHeader("X-Trace-Id")).isEqualTo("0af7651916cd43dd8448eb211c80319c");
        assertThat(seenHeaders.get("X-trace-id")).containsExactly("0af7651916cd43dd8448eb211c80319c");
    }

    @Test
    void preservesMultiValuedHeaders() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/echo");
        req.setContent("x".getBytes(StandardCharsets.UTF_8));
        req.addHeader("X-Multi", "a");
        req.addHeader("X-Multi", "b");

        run(proxy(url(), 0), req);

        assertThat(seenHeaders.get("X-multi")).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void idempotentGetIsRetriedOnGatewayErrorThenSucceeds() throws Exception {
        firstCallStatus = 503;                     // upstream flaps once, then recovers
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/x");

        MockHttpServletResponse res = run(proxy(url(), 0, 2, new CircuitBreakerRegistry(5, 10)), req);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(calls.get()).isEqualTo(2);      // retried
    }

    @Test
    void postIsNeverRetried() throws Exception {
        firstCallStatus = 503;
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/echo");
        req.setContent("x".getBytes(StandardCharsets.UTF_8));

        MockHttpServletResponse res = run(proxy(url(), 0, 2, new CircuitBreakerRegistry(5, 10)), req);

        assertThat(res.getStatus()).isEqualTo(503);
        assertThat(calls.get()).isEqualTo(1);      // not retried despite retry budget
    }

    @Test
    void circuitOpensAfterThresholdThenFastFails() throws Exception {
        CircuitBreakerRegistry breakers = new CircuitBreakerRegistry(1, 10);   // trip after one failure
        GatewayProxy proxy = proxy("http://127.0.0.1:1", 0, 1, breakers);      // dead upstream

        MockHttpServletResponse first = run(proxy, new MockHttpServletRequest("GET", "/api/x"));
        MockHttpServletResponse second = run(proxy, new MockHttpServletRequest("GET", "/api/x"));

        assertThat(first.getStatus()).isEqualTo(502);    // transport failure
        assertThat(second.getStatus()).isEqualTo(503);   // circuit open -> fast fail
    }

    @Test
    void unknownPathIsNotFound() throws Exception {
        MockHttpServletResponse res = run(proxy(url(), 0), new MockHttpServletRequest("GET", "/nope"));
        assertThat(res.getStatus()).isEqualTo(404);
    }

    @Test
    void oversizeRequestIsRejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/echo");
        req.setContent("hello".getBytes(StandardCharsets.UTF_8));
        MockHttpServletResponse res = run(proxy(url(), 4), req);
        assertThat(res.getStatus()).isEqualTo(413);
    }
}
