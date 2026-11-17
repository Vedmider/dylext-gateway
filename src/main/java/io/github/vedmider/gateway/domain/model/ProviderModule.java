package io.github.vedmider.gateway.domain.model;

import java.util.Set;

/**
 * A backend module behind the gateway, described protocol-agnostically: it owns a URL path prefix
 * and an upstream base URL. The gateway forwards raw HTTP for matching paths — it does not know or
 * care whether the upstream speaks MCP/JSON-RPC, REST, or anything else. Capabilities are advertised
 * only for discovery.
 */
public record ProviderModule(String name, String pathPrefix, String upstreamBaseUrl,
                             Set<Capability> capabilities) {

    public ProviderModule {
        capabilities = Set.copyOf(capabilities);
    }

    public boolean matches(String path) {
        return path.equals(pathPrefix) || path.startsWith(pathPrefix.endsWith("/") ? pathPrefix : pathPrefix + "/");
    }
}
