package io.github.vedmider.gateway.adapter.in.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.net.InetAddress;
import java.net.UnknownHostException;

/**
 * Binds the proxied surface ({@code /mcp}, {@code /api}) to loopback callers by default, so the
 * gateway is not an open relay to the backend providers. A non-loopback caller is rejected with 403
 * unless it presents the configured {@code X-Gateway-Token}. Actuator and other paths are untouched.
 */
@Component
public class LoopbackAccessFilter extends OncePerRequestFilter {

    private final boolean loopbackOnly;
    private final String token;

    public LoopbackAccessFilter(@Value("${gateway.security.loopback-only:true}") boolean loopbackOnly,
                                @Value("${gateway.security.token:}") String token) {
        this.loopbackOnly = loopbackOnly;
        this.token = token;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain chain) throws ServletException, IOException {
        if (!loopbackOnly || !guarded(request.getRequestURI())
                || isLoopback(request.getRemoteAddr()) || hasValidToken(request)) {
            chain.doFilter(request, response);
            return;
        }
        response.sendError(HttpServletResponse.SC_FORBIDDEN,
                "gateway: non-loopback access denied");
    }

    private static boolean guarded(String path) {
        return path.equals("/mcp") || path.startsWith("/mcp/") || path.startsWith("/api/");
    }

    private boolean hasValidToken(HttpServletRequest request) {
        if (token.isBlank()) {
            return false;
        }
        String presented = request.getHeader("X-Gateway-Token");
        return presented != null && java.security.MessageDigest.isEqual(
                token.getBytes(java.nio.charset.StandardCharsets.UTF_8),
                presented.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    private static boolean isLoopback(String remoteAddr) {
        if (remoteAddr == null) {
            return false;
        }
        try {
            return InetAddress.getByName(remoteAddr).isLoopbackAddress();
        } catch (UnknownHostException e) {
            return false;
        }
    }
}
