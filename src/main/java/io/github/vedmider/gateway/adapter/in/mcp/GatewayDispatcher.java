package io.github.vedmider.gateway.adapter.in.mcp;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.vedmider.gateway.adapter.out.http.DylextHttpProvider.ProviderException;
import io.github.vedmider.gateway.application.Gateway;
import org.springframework.stereotype.Component;

/**
 * Minimal JSON-RPC 2.0 front door. Transport-agnostic: {@link #handle} maps a request string to a
 * response string. {@code initialize} is answered locally; {@code tools/list} and {@code tools/call}
 * are routed through the {@link Gateway} to the owning provider, returning its result verbatim.
 */
@Component
public class GatewayDispatcher {

    private final ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    private final Gateway gateway;

    public GatewayDispatcher(Gateway gateway) {
        this.gateway = gateway;
    }

    /** @return the JSON-RPC response string, or null for a notification (no id). */
    public String handle(String requestJson) {
        JsonNode req;
        try {
            req = json.readTree(requestJson);
        } catch (Exception e) {
            return error(null, -32700, "parse error");
        }
        JsonNode id = req.get("id");
        String method = req.path("method").asText("");
        try {
            return switch (method) {
                case "initialize" -> ok(id, initializeResult());
                case "tools/list" -> ok(id, toolsList());
                case "tools/call" -> ok(id, callTool(req.path("params")));
                case "" -> error(id, -32600, "invalid request: missing method");
                default -> id == null ? null : error(id, -32601, "method not found: " + method);
            };
        } catch (IllegalArgumentException bad) {
            return error(id, -32602, bad.getMessage());
        } catch (ProviderException upstream) {
            return error(id, -32603, upstream.getMessage());
        } catch (Exception e) {
            return error(id, -32603, e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    private ObjectNode initializeResult() {
        ObjectNode r = json.createObjectNode();
        r.put("protocolVersion", "2024-11-05");
        r.putObject("capabilities").putObject("tools");
        r.putObject("serverInfo").put("name", "dylext-gateway").put("version", "0.1.0");
        return r;
    }

    private ObjectNode toolsList() {
        ObjectNode r = json.createObjectNode();
        r.set("tools", gateway.aggregatedTools());
        return r;
    }

    private JsonNode callTool(JsonNode params) {
        String name = params.path("name").asText("");
        if (name.isBlank()) {
            throw new IllegalArgumentException("missing required argument: name");
        }
        return gateway.callTool(name, params.path("arguments"));
    }

    private String ok(JsonNode id, JsonNode result) {
        ObjectNode r = json.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id == null ? null : id);
        r.set("result", result);
        return r.toString();
    }

    private String error(JsonNode id, int code, String message) {
        ObjectNode r = json.createObjectNode();
        r.put("jsonrpc", "2.0");
        r.set("id", id == null ? null : id);
        ObjectNode err = r.putObject("error");
        err.put("code", code);
        err.put("message", message);
        return r.toString();
    }
}
