package io.github.vedmider.gateway;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Boots the whole gateway (config binding + bean wiring + real HTTP path) and proves the new ingress
 * protection works end-to-end, not just in isolated component tests. Rate-limited so the second call
 * to a healthy stub upstream is rejected with 429 by the booted gateway.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "gateway.rate-limit.requests-per-second=0.001",
        "gateway.rate-limit.burst=1"
})
class GatewayIntegrationTest {

    private static HttpServer upstream;

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate rest;

    @DynamicPropertySource
    static void providerUrls(DynamicPropertyRegistry registry) {
        registry.add("gateway.providers.sestacon-url",
                () -> "http://127.0.0.1:" + upstream.getAddress().getPort());
    }

    @BeforeAll
    static void startUpstream() throws Exception {
        upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        upstream.createContext("/api", exchange -> {
            byte[] body = "ok".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body);
            exchange.close();
        });
        upstream.start();
    }

    @AfterAll
    static void stopUpstream() {
        upstream.stop(0);
    }

    @Test
    void bootsAndEnforcesRateLimitThroughTheFullHttpStack() {
        String url = "http://127.0.0.1:" + port + "/api/thing";
        ResponseEntity<String> first = rest.getForEntity(url, String.class);
        ResponseEntity<String> second = rest.getForEntity(url, String.class);

        assertThat(first.getStatusCode().value()).isEqualTo(200);       // burst token
        assertThat(second.getStatusCode().value()).isEqualTo(429);      // rate-limited by the booted gateway
        assertThat(second.getHeaders().getFirst("Retry-After")).isNotNull();
    }
}
