package io.github.vedmider.gateway.adapter.in.web;

import io.github.vedmider.gateway.application.McpAggregator;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

/**
 * The gateway's unified MCP endpoint. Terminates MCP (JSON-RPC) and presents Dylext + Sestacon as a
 * single toolset via {@link McpAggregator}. A {@code POST /mcp} is handled here; everything else
 * ({@code /api/**}) is still forwarded raw by {@link ProxyController}.
 */
@RestController
public class McpAggregatorController {

    private final McpAggregator aggregator;

    public McpAggregatorController(McpAggregator aggregator) {
        this.aggregator = aggregator;
    }

    @PostMapping(value = "/mcp", produces = "application/json")
    public ResponseEntity<String> mcp(@RequestBody(required = false) String body) {
        String response = aggregator.handle(body);
        if (response == null) {
            return ResponseEntity.noContent().build();   // JSON-RPC notification: no response
        }
        return ResponseEntity.ok(response);
    }
}
