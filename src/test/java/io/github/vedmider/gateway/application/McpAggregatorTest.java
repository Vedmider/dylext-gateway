package io.github.vedmider.gateway.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class McpAggregatorTest {

    private HttpServer stub;
    private McpAggregator aggregator;
    private String base;
    private final ObjectMapper json = new ObjectMapper();
    private final AtomicReference<String> lastMcpBody = new AtomicReference<>();
    private final AtomicReference<String> lastApiPath = new AtomicReference<>();
    private final AtomicReference<String> lastApiBody = new AtomicReference<>();
    private final AtomicReference<String> lastApiRoles = new AtomicReference<>();

    @BeforeEach
    void startStub() throws Exception {
        stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        stub.createContext("/mcp", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastMcpBody.set(body);
            String resp = body.contains("\"tools/list\"")
                    ? "{\"jsonrpc\":\"2.0\",\"id\":1,\"result\":{\"tools\":["
                        + "{\"name\":\"search_symbols\",\"description\":\"find\",\"inputSchema\":{\"type\":\"object\"}},"
                        + "{\"name\":\"impact_of\",\"description\":\"blast\",\"inputSchema\":{\"type\":\"object\"}}]}}"
                    : "{\"jsonrpc\":\"2.0\",\"id\":7,\"result\":{\"content\":[{\"type\":\"text\","
                        + "\"json\":{\"forwarded\":true}}],\"tokens\":{\"calls\":1}}}";
            respond(exchange, 200, resp);
        });
        stub.createContext("/api", exchange -> {
            lastApiPath.set(exchange.getRequestMethod() + " " + exchange.getRequestURI());
            try (InputStream in = exchange.getRequestBody()) {
                lastApiBody.set(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            }
            List<String> roles = exchange.getRequestHeaders().get("X-Roles");
            lastApiRoles.set(roles == null ? null : String.join(",", roles));
            respond(exchange, 200, "[{\"text\":\"chunk about zorblax\",\"source\":\"doc.md\",\"score\":0.9}]");
        });
        stub.start();
        base = "http://127.0.0.1:" + stub.getAddress().getPort();
        aggregator = new McpAggregator(new RouteRegistry(base, base),
                new CircuitBreakerRegistry(5, 10_000), new RateLimiterRegistry(0, 0),
                new BulkheadRegistry(0, 0), 5);
    }

    @AfterEach
    void stopStub() {
        stub.stop(0);
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body)
            throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        exchange.getResponseBody().write(bytes);
        exchange.close();
    }

    private JsonNode call(String requestJson) throws Exception {
        return json.readTree(aggregator.handle(requestJson));
    }

    @Test
    void initializeReportsGatewayAsTheMcpServer() throws Exception {
        JsonNode r = call("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\"}");
        assertThat(r.path("result").path("serverInfo").path("name").asText()).isEqualTo("dylext-gateway");
        assertThat(r.path("result").path("capabilities").has("tools")).isTrue();
    }

    @Test
    void toolsListMergesDylextToolsAndSestaconTools() throws Exception {
        JsonNode r = call("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"tools/list\"}");
        List<String> names = new ArrayList<>();
        r.path("result").path("tools").forEach(t -> names.add(t.path("name").asText()));
        assertThat(names).contains("search_symbols", "impact_of",
                "sestacon_retrieve", "sestacon_chat", "sestacon_ingest", "sestacon_delete", "sestacon_version");
    }

    @Test
    void sestaconRetrieveIsTranslatedToRestAndWrappedAsAnMcpResult() throws Exception {
        JsonNode r = call("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"sestacon_retrieve\",\"arguments\":{\"query\":\"zorblax\",\"topK\":3,"
                + "\"roles\":[\"FINANCE\",\"LEGAL\"]}}}");

        assertThat(lastApiPath.get()).isEqualTo("POST /api/retrieve");
        assertThat(lastApiBody.get()).contains("zorblax").contains("\"topK\":3");
        assertThat(lastApiRoles.get()).isEqualTo("FINANCE,LEGAL");
        JsonNode content = r.path("result").path("content").get(0).path("json");
        assertThat(content.get(0).path("source").asText()).isEqualTo("doc.md");
    }

    @Test
    void aDylextToolCallIsForwardedVerbatimToDylext() throws Exception {
        JsonNode r = call("{\"jsonrpc\":\"2.0\",\"id\":7,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"search_symbols\",\"arguments\":{\"repo\":\"x\",\"query\":\"y\"}}}");

        assertThat(lastMcpBody.get()).contains("\"tools/call\"").contains("search_symbols");
        assertThat(r.path("result").path("content").get(0).path("json").path("forwarded").asBoolean()).isTrue();
        assertThat(r.path("result").path("tokens").path("calls").asInt()).isEqualTo(1);
    }

    @Test
    void aMissingRequiredArgumentIsAClientError() throws Exception {
        JsonNode r = call("{\"jsonrpc\":\"2.0\",\"id\":8,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"sestacon_retrieve\",\"arguments\":{}}}");
        assertThat(r.path("error").path("code").asInt()).isEqualTo(-32602);
    }

    @Test
    void unknownMethodIsMethodNotFoundAndBadJsonIsParseError() throws Exception {
        assertThat(call("{\"jsonrpc\":\"2.0\",\"id\":9,\"method\":\"frobnicate\"}")
                .path("error").path("code").asInt()).isEqualTo(-32601);
        assertThat(call("{not json").path("error").path("code").asInt()).isEqualTo(-32700);
    }

    @Test
    void aNotificationGetsNoResponse() {
        assertThat(aggregator.handle(
                "{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}")).isNull();
    }

    @Test
    void anUpstreamGuardRejectionIsReturnedAsAJsonRpcError() throws Exception {
        // A capacity-1 bucket (rps ~0): the first sestacon call passes, the second is rate-limited —
        // proving MCP upstream calls get the same per-provider guards as the REST proxy.
        McpAggregator limited = new McpAggregator(new RouteRegistry(base, base),
                new CircuitBreakerRegistry(5, 10_000), new RateLimiterRegistry(0.001, 1),
                new BulkheadRegistry(0, 0), 5);
        String req = "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{"
                + "\"name\":\"sestacon_retrieve\",\"arguments\":{\"query\":\"x\"}}}";

        assertThat(json.readTree(limited.handle(req)).has("result")).isTrue();
        JsonNode second = json.readTree(limited.handle(req));
        assertThat(second.path("error").path("code").asInt()).isEqualTo(-32603);
        assertThat(second.path("error").path("message").asText()).contains("rate limit");
    }
}
