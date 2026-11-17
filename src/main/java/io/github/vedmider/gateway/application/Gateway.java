package io.github.vedmider.gateway.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import io.github.vedmider.gateway.domain.model.CapabilityDescriptor;
import io.github.vedmider.gateway.domain.port.out.ContextProvider;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * The thin, stateless router: it picks the provider that owns a tool, forwards the call, and (when
 * enabled) records per-call telemetry. It holds no state and implements no domain logic — the
 * response is whatever the provider returns. Horizontally scalable; any state (cache) is external.
 */
@Service
public class Gateway {

    private final List<ContextProvider> providers;
    private final MeterRegistry metrics;
    private final boolean telemetryEnabled;
    private final ObjectMapper json = new ObjectMapper();

    public Gateway(List<ContextProvider> providers, MeterRegistry metrics,
                   @Value("${gateway.telemetry-enabled:true}") boolean telemetryEnabled) {
        this.providers = List.copyOf(providers);
        this.metrics = metrics;
        this.telemetryEnabled = telemetryEnabled;
    }

    /** Route a tool call to its owning provider; record telemetry; return the provider's result. */
    public JsonNode callTool(String tool, JsonNode arguments) {
        ContextProvider provider = providerFor(tool);
        long start = System.nanoTime();
        String outcome = "ok";
        try {
            return provider.callTool(tool, arguments);
        } catch (RuntimeException e) {
            outcome = "error";
            throw e;
        } finally {
            if (telemetryEnabled) {
                metrics.timer("gateway.calls", "provider", provider.name(), "tool", tool, "outcome", outcome)
                        .record(System.nanoTime() - start, TimeUnit.NANOSECONDS);
            }
        }
    }

    /** Merged {@code tools} array across all providers (for {@code tools/list}). */
    public ArrayNode aggregatedTools() {
        ArrayNode merged = json.createArrayNode();
        for (ContextProvider p : providers) {
            JsonNode tools = p.toolsList();
            if (tools != null && tools.isArray()) {
                tools.forEach(merged::add);
            }
        }
        return merged;
    }

    public List<CapabilityDescriptor> capabilities() {
        return providers.stream().map(ContextProvider::describe).toList();
    }

    private ContextProvider providerFor(String tool) {
        return providers.stream().filter(p -> p.handles(tool)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("no provider for tool: " + tool));
    }
}
