package io.github.vedmider.gateway.adapter.in.mcp;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HTTP transport for the MCP front door: {@code POST /mcp} with a JSON-RPC body. A notification
 * (no id) yields 204. Bind loopback-only in deployment (add an access filter, as Dylext does).
 */
@RestController
@RequestMapping("/mcp")
public class McpGatewayController {

    private final GatewayDispatcher dispatcher;

    public McpGatewayController(GatewayDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<String> handle(@RequestBody String body) {
        String response = dispatcher.handle(body);
        if (response == null) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok().contentType(MediaType.APPLICATION_JSON).body(response);
    }
}
