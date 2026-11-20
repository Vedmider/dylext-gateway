package io.github.vedmider.gateway.adapter.in.web;

import io.github.vedmider.gateway.application.GatewayProxy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transparent reverse proxy for provider REST paths ({@code /api/**}), forwarded raw by
 * {@link GatewayProxy}; actuator and other local endpoints are untouched. Protocol-agnostic — this
 * controller reads no body and knows no application protocol. MCP ({@code POST /mcp}) is NOT proxied
 * here: it is terminated and aggregated by {@link McpAggregatorController}.
 */
@RestController
public class ProxyController {

    private final GatewayProxy proxy;

    public ProxyController(GatewayProxy proxy) {
        this.proxy = proxy;
    }

    @RequestMapping({"/api/**"})
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws Exception {
        proxy.forward(request, response);
    }
}
