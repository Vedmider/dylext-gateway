package io.github.vedmider.gateway.adapter.in.web;

import io.github.vedmider.gateway.application.GatewayProxy;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Single ingress. Every provider-bound path ({@code /mcp**}, {@code /api/**}) is forwarded raw by
 * {@link GatewayProxy}; actuator and other local endpoints are untouched. Protocol-agnostic — this
 * controller reads no body and knows no application protocol.
 */
@RestController
public class ProxyController {

    private final GatewayProxy proxy;

    public ProxyController(GatewayProxy proxy) {
        this.proxy = proxy;
    }

    @RequestMapping({"/mcp", "/mcp/**", "/api/**"})
    public void proxy(HttpServletRequest request, HttpServletResponse response) throws Exception {
        proxy.forward(request, response);
    }
}
