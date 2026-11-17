package io.github.vedmider.gateway.application;

import io.github.vedmider.gateway.domain.model.Capability;
import io.github.vedmider.gateway.domain.model.ProviderModule;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * The route table: path prefix -> provider module (upstream). Built from config so adding a module
 * is config + one entry, never a code change in the proxy core. Longest-prefix wins.
 */
@Component
public class RouteRegistry {

    private final List<ProviderModule> modules = new ArrayList<>();

    public RouteRegistry(
            @Value("${gateway.providers.dylext-url:http://localhost:18090}") String dylextUrl,
            @Value("${gateway.providers.sestacon-url:http://localhost:18080}") String sestaconUrl) {
        // Dylext speaks MCP/JSON-RPC at /mcp; Sestacon speaks REST at /api — the gateway treats both
        // as opaque HTTP upstreams.
        modules.add(new ProviderModule("dylext", "/mcp", stripTrailing(dylextUrl),
                EnumSet.of(Capability.STRUCTURE, Capability.SPAN, Capability.IMPACT, Capability.LIVE,
                        Capability.CONCEPT, Capability.FUZZY, Capability.DOC, Capability.VERIFY,
                        Capability.INDEX, Capability.SESSION, Capability.METER)));
        modules.add(new ProviderModule("sestacon", "/api", stripTrailing(sestaconUrl),
                EnumSet.of(Capability.DOC, Capability.CONCEPT)));
    }

    /** The module owning a request path (longest matching prefix), or empty if none. */
    public Optional<ProviderModule> match(String path) {
        return modules.stream().filter(m -> m.matches(path))
                .max((a, b) -> Integer.compare(a.pathPrefix().length(), b.pathPrefix().length()));
    }

    public List<ProviderModule> modules() {
        return List.copyOf(modules);
    }

    private static String stripTrailing(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
