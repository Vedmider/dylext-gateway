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

import static org.assertj.core.api.Assertions.assertThat;

class GatewayProxyTest {

    private HttpServer upstream;
    private int port;
    private volatile String seenMethod;
    private volatile String seenUri;
    private volatile String seenBody;
    private final Map<String, List<String>> seenHeaders = new ConcurrentHashMap<>();

    @BeforeEach
    void startUpstream() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/api", exchange -> {
            seenMethod = exchange.getRequestMethod();
            seenUri = exchange.getRequestURI().toString();
            try (InputStream in = exchange.getRequestBody()) {
                seenBody = new String(in.readAllBytes(), StandardCharsets.UTF_8);
            }
            exchange.getRequestHeaders().forEach(seenHeaders::put);
            byte[] body = "upstream-body".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("X-Up", "yes");
            exchange.sendResponseHeaders(200, body.length);
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
        RouteRegistry routes = new RouteRegistry("http://127.0.0.1:1", sestaconUrl);
        return new GatewayProxy(routes, new SimpleMeterRegistry(), false, 2, 60, maxBytes);
    }

    private MockHttpServletResponse run(GatewayProxy proxy, MockHttpServletRequest req) throws Exception {
        MockHttpServletResponse res = new MockHttpServletResponse();
        proxy.forward(req, res);
        return res;
    }

    @Test
    void forwardsMethodPathQueryBodyAndReturnsUpstreamStatusBodyHeaders() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/echo");
        req.setQueryString("x=1");
        req.setContent("hello".getBytes(StandardCharsets.UTF_8));
        req.setRemoteAddr("1.2.3.4");
        req.addHeader("X-Roles", "admin");
        req.addHeader("Connection", "keep-alive");   // hop-by-hop: must NOT reach upstream

        MockHttpServletResponse res = run(proxy("http://127.0.0.1:" + port, 0), req);

        assertThat(res.getStatus()).isEqualTo(200);
        assertThat(res.getContentAsString()).isEqualTo("upstream-body");
        assertThat(res.getHeader("X-Up")).isEqualTo("yes");
        assertThat(seenMethod).isEqualTo("POST");
        assertThat(seenUri).isEqualTo("/api/echo?x=1");
        assertThat(seenBody).isEqualTo("hello");
        assertThat(seenHeaders).containsKey("X-roles");          // com.sun normalises to Title-case
        assertThat(seenHeaders.getOrDefault("Connection", List.of()))
                .doesNotContain("keep-alive");                   // client hop-by-hop value did not propagate
    }

    @Test
    void addsForwardingHeadersAndEchoesRequestId() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/echo");
        req.setContent("x".getBytes(StandardCharsets.UTF_8));
        req.setRemoteAddr("1.2.3.4");
        req.addHeader("Host", "gw:18070");

        MockHttpServletResponse res = run(proxy("http://127.0.0.1:" + port, 0), req);

        assertThat(seenHeaders.get("X-forwarded-for")).containsExactly("1.2.3.4");
        assertThat(seenHeaders.get("X-forwarded-proto")).containsExactly("http");
        assertThat(seenHeaders.get("X-forwarded-host")).containsExactly("gw:18070");
        assertThat(seenHeaders.get("X-request-id")).hasSize(1);
        assertThat(res.getHeader("X-Request-Id")).isNotBlank();   // echoed to caller
    }

    @Test
    void preservesMultiValuedHeaders() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/echo");
        req.setContent("x".getBytes(StandardCharsets.UTF_8));
        req.addHeader("X-Multi", "a");
        req.addHeader("X-Multi", "b");

        run(proxy("http://127.0.0.1:" + port, 0), req);

        assertThat(seenHeaders.get("X-multi")).containsExactlyInAnyOrder("a", "b");
    }

    @Test
    void unknownPathIsNotFound() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/nope");
        MockHttpServletResponse res = run(proxy("http://127.0.0.1:" + port, 0), req);
        assertThat(res.getStatus()).isEqualTo(404);
    }

    @Test
    void unreachableUpstreamIsBadGateway() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/x");
        MockHttpServletResponse res = run(proxy("http://127.0.0.1:1", 0), req);   // port 1 refused
        assertThat(res.getStatus()).isEqualTo(502);
    }

    @Test
    void oversizeRequestIsRejected() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("POST", "/api/echo");
        req.setContent("hello".getBytes(StandardCharsets.UTF_8));   // 5 bytes
        MockHttpServletResponse res = run(proxy("http://127.0.0.1:" + port, 4), req);
        assertThat(res.getStatus()).isEqualTo(413);
    }
}
