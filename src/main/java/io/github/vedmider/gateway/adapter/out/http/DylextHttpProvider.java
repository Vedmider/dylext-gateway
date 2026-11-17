package io.github.vedmider.gateway.adapter.out.http;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.github.vedmider.gateway.domain.model.Capability;
import io.github.vedmider.gateway.domain.model.CapabilityDescriptor;
import io.github.vedmider.gateway.domain.port.out.ContextProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.EnumSet;

/**
 * Routes to Dylext's JSON-RPC {@code /mcp} endpoint (same transport as the {@code dx} client).
 * Phase 1: Dylext is the sole backend and already federates explain/recall/answer, so this provider
 * {@link #handles} every tool. Later increments add Sestacon / code-memory providers and move the
 * doc/concept/fuzzy tools off Dylext.
 */
@Component
public class DylextHttpProvider implements ContextProvider {

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final String mcpUrl;

    public DylextHttpProvider(@Value("${gateway.providers.dylext-url:http://localhost:18090}") String dylextUrl) {
        this.mcpUrl = dylextUrl.endsWith("/mcp") ? dylextUrl : dylextUrl + "/mcp";
    }

    @Override
    public String name() {
        return "dylext";
    }

    @Override
    public CapabilityDescriptor describe() {
        return new CapabilityDescriptor(name(), EnumSet.allOf(Capability.class));
    }

    @Override
    public boolean handles(String tool) {
        return true;
    }

    @Override
    public JsonNode toolsList() {
        JsonNode result = rpc("tools/list", null);
        return result.path("tools");
    }

    @Override
    public JsonNode callTool(String tool, JsonNode arguments) {
        ObjectNode params = json.createObjectNode();
        params.put("name", tool);
        params.set("arguments", arguments == null ? json.createObjectNode() : arguments);
        return rpc("tools/call", params);
    }

    /** One JSON-RPC round trip; returns {@code result} or throws with the backend's error message. */
    private JsonNode rpc(String method, JsonNode params) {
        ObjectNode req = json.createObjectNode();
        req.put("jsonrpc", "2.0");
        req.put("id", 1);
        req.put("method", method);
        if (params != null) {
            req.set("params", params);
        }
        try {
            HttpResponse<String> resp = http.send(
                    HttpRequest.newBuilder(URI.create(mcpUrl))
                            .timeout(Duration.ofSeconds(30))
                            .header("content-type", "application/json")
                            .POST(HttpRequest.BodyPublishers.ofString(req.toString()))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            JsonNode body = json.readTree(resp.body());
            if (body.has("error")) {
                throw new ProviderException(body.path("error").path("message").asText("provider error"));
            }
            return body.path("result");
        } catch (ProviderException e) {
            throw e;
        } catch (Exception unreachable) {
            throw new ProviderException("dylext provider unreachable at " + mcpUrl);
        }
    }

    /** Signals a provider-side failure the gateway maps onto a JSON-RPC error. */
    public static class ProviderException extends RuntimeException {
        public ProviderException(String message) {
            super(message);
        }
    }
}
