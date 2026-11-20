package io.github.vedmider.gateway.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.vedmider.gateway.domain.model.ProviderModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * MCP aggregator: terminates MCP (JSON-RPC 2.0) at the gateway's {@code /mcp} and presents the whole
 * ecosystem as one toolset. Dylext tool calls are forwarded verbatim to Dylext's own {@code /mcp}
 * (keeping its epoch/cache/ledger envelope); Sestacon's REST API is exposed as MCP tools (prefix
 * {@code sestacon_}) and each call is translated to the matching {@code /api} request. An MCP client
 * connects once and sees Dylext + Sestacon together; the LLM orchestrates both (plus verify_claims).
 */
@Service
public class McpAggregator {

    private static final Logger log = LoggerFactory.getLogger(McpAggregator.class);
    private static final String PROTOCOL_VERSION = "2024-11-05";
    static final String SESTACON_PREFIX = "sestacon_";

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient http;
    private final String dylextMcpUrl;
    private final String sestaconBaseUrl;
    private final Duration timeout;

    public McpAggregator(RouteRegistry routes,
                         @Value("${gateway.upstream-timeout-seconds:60}") long timeoutSeconds) {
        this.dylextMcpUrl = upstream(routes, "dylext") + "/mcp";
        this.sestaconBaseUrl = upstream(routes, "sestacon");
        this.timeout = Duration.ofSeconds(timeoutSeconds);
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    }

    private static String upstream(RouteRegistry routes, String name) {
        return routes.modules().stream().filter(m -> m.name().equals(name))
                .map(ProviderModule::upstreamBaseUrl).findFirst()
                .orElseThrow(() -> new IllegalStateException("no upstream configured: " + name));
    }

    /** Handle one JSON-RPC request; returns the JSON-RPC response text, or null for a notification. */
    public String handle(String requestJson) {
        JsonNode req;
        try {
            req = json.readTree(requestJson == null ? "" : requestJson);
        } catch (Exception parseError) {
            return error(null, -32700, "parse error");
        }
        JsonNode id = req.get("id");
        String method = req.path("method").asText("");
        try {
            return switch (method) {
                case "initialize" -> ok(id, initializeResult());
                case "tools/list" -> ok(id, toolsList());
                case "tools/call" -> callTool(id, req);
                case "" -> error(id, -32600, "invalid request: missing method");
                default -> {
                    if (method.startsWith("notifications/")) {
                        yield null;   // JSON-RPC notifications get no response
                    }
                    yield id == null ? null : error(id, -32601, "method not found: " + method);
                }
            };
        } catch (IllegalArgumentException bad) {
            return error(id, -32602, bad.getMessage());
        } catch (Exception e) {
            return error(id, -32603, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private ObjectNode initializeResult() {
        ObjectNode r = json.createObjectNode();
        r.put("protocolVersion", PROTOCOL_VERSION);
        r.putObject("capabilities").putObject("tools");
        ObjectNode info = r.putObject("serverInfo");
        info.put("name", "dylext-gateway");
        info.put("version", "0.2.0");
        return r;
    }

    private ObjectNode toolsList() {
        ObjectNode result = json.createObjectNode();
        ArrayNode tools = result.putArray("tools");
        try {
            HttpResponse<String> r = send("POST", dylextMcpUrl,
                    "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}", null);
            JsonNode dylextTools = json.readTree(r.body()).path("result").path("tools");
            if (dylextTools.isArray()) {
                dylextTools.forEach(tools::add);
            }
        } catch (Exception e) {
            // Degrade gracefully: still advertise Sestacon tools if Dylext is momentarily unreachable.
            log.warn("dylext tools/list unavailable, serving sestacon tools only: {}", e.toString());
        }
        sestaconTools().forEach(tools::add);
        return result;
    }

    private String callTool(JsonNode id, JsonNode req) throws Exception {
        JsonNode params = req.path("params");
        String name = params.path("name").asText("");
        if (name.startsWith(SESTACON_PREFIX)) {
            return ok(id, sestaconCall(name, params.path("arguments")));
        }
        // Dylext tool: forward the original JSON-RPC verbatim and return Dylext's response as-is
        // (its id/tokens/epoch/cached envelope is preserved).
        return send("POST", dylextMcpUrl, req.toString(), null).body();
    }

    private ObjectNode sestaconCall(String name, JsonNode args) throws Exception {
        String roles = rolesHeader(args);
        HttpResponse<String> r = switch (name) {
            case "sestacon_retrieve" -> {
                ObjectNode body = json.createObjectNode();
                body.set("query", requireText(args, "query"));
                if (args.has("topK")) {
                    body.set("topK", args.get("topK"));
                }
                yield send("POST", sestaconBaseUrl + "/api/retrieve", body.toString(), roles);
            }
            case "sestacon_chat" -> {
                ObjectNode body = json.createObjectNode();
                body.set("question", requireText(args, "question"));
                yield send("POST", sestaconBaseUrl + "/api/chat", body.toString(), roles);
            }
            case "sestacon_ingest" -> {
                if (!args.path("chunks").isArray()) {
                    throw new IllegalArgumentException("sestacon_ingest requires a 'chunks' array");
                }
                String q = args.hasNonNull("sourceSystem")
                        ? "?sourceSystem=" + enc(args.get("sourceSystem").asText()) : "";
                yield send("POST", sestaconBaseUrl + "/api/documents" + q, args.get("chunks").toString(), roles);
            }
            case "sestacon_delete" -> send("DELETE",
                    sestaconBaseUrl + "/api/documents/" + enc(requireText(args, "documentId").asText()), null, roles);
            case "sestacon_version" -> send("GET",
                    sestaconBaseUrl + "/api/documents/" + enc(requireText(args, "documentId").asText()) + "/version",
                    null, roles);
            default -> throw new IllegalArgumentException("unknown sestacon tool: " + name);
        };
        if (r.statusCode() >= 400) {
            throw new IllegalStateException(name + " -> HTTP " + r.statusCode() + ": " + r.body());
        }
        ObjectNode result = json.createObjectNode();
        ObjectNode item = result.putArray("content").addObject();
        item.put("type", "text");
        try {
            item.set("json", json.readTree(r.body()));
        } catch (Exception notJson) {
            item.put("text", r.body());
        }
        return result;
    }

    private List<ObjectNode> sestaconTools() {
        return List.of(
                tool("sestacon_retrieve",
                        "Retrieve the most relevant Sestacon document chunks for a query (RAG, no LLM).",
                        "{\"type\":\"object\",\"properties\":{\"query\":{\"type\":\"string\"},"
                                + "\"topK\":{\"type\":\"integer\"},"
                                + "\"roles\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},"
                                + "\"required\":[\"query\"]}"),
                tool("sestacon_chat",
                        "Ask a question answered grounded in Sestacon's documents, with sources.",
                        "{\"type\":\"object\",\"properties\":{\"question\":{\"type\":\"string\"},"
                                + "\"roles\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},"
                                + "\"required\":[\"question\"]}"),
                tool("sestacon_ingest",
                        "Ingest pre-chunked documents into Sestacon (JSON chunk contract).",
                        "{\"type\":\"object\",\"properties\":{\"chunks\":{\"type\":\"array\","
                                + "\"items\":{\"type\":\"object\"}},\"sourceSystem\":{\"type\":\"string\"}},"
                                + "\"required\":[\"chunks\"]}"),
                tool("sestacon_delete",
                        "Delete a document and all its chunks by id (all-or-nothing ACL by roles).",
                        "{\"type\":\"object\",\"properties\":{\"documentId\":{\"type\":\"string\"},"
                                + "\"roles\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},"
                                + "\"required\":[\"documentId\"]}"),
                tool("sestacon_version",
                        "Get a document's stored version identity (hashes/size); ACL-gated by roles.",
                        "{\"type\":\"object\",\"properties\":{\"documentId\":{\"type\":\"string\"},"
                                + "\"roles\":{\"type\":\"array\",\"items\":{\"type\":\"string\"}}},"
                                + "\"required\":[\"documentId\"]}"));
    }

    private ObjectNode tool(String name, String description, String inputSchemaJson) {
        ObjectNode t = json.createObjectNode();
        t.put("name", name);
        t.put("description", description);
        try {
            t.set("inputSchema", json.readTree(inputSchemaJson));
        } catch (Exception e) {
            throw new IllegalStateException("bad tool schema for " + name, e);
        }
        return t;
    }

    private HttpResponse<String> send(String method, String url, String body, String roles) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(timeout);
        if (roles != null && !roles.isBlank()) {
            b.header("X-Roles", roles);
        }
        if (body != null) {
            b.header("Content-Type", "application/json");
            b.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        } else {
            b.method(method, HttpRequest.BodyPublishers.noBody());
        }
        return http.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static JsonNode requireText(JsonNode args, String field) {
        JsonNode v = args.get(field);
        if (v == null || v.isNull() || (v.isTextual() && v.asText().isBlank())) {
            throw new IllegalArgumentException("missing required argument: " + field);
        }
        return v;
    }

    private static String rolesHeader(JsonNode args) {
        JsonNode roles = args.path("roles");
        if (roles.isArray()) {
            List<String> list = new ArrayList<>();
            roles.forEach(n -> list.add(n.asText()));
            return String.join(",", list);
        }
        return roles.isTextual() ? roles.asText() : null;
    }

    private static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private String ok(JsonNode id, JsonNode result) {
        ObjectNode r = json.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id == null ? json.nullNode() : id);
        r.set("result", result);
        return r.toString();
    }

    private String error(JsonNode id, int code, String message) {
        ObjectNode r = json.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id == null ? json.nullNode() : id);
        ObjectNode err = r.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return r.toString();
    }
}
