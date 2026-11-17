package io.github.vedmider.gateway.application;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.github.vedmider.gateway.domain.model.Capability;
import io.github.vedmider.gateway.domain.model.CapabilityDescriptor;
import io.github.vedmider.gateway.domain.port.out.ContextProvider;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;

import java.util.EnumSet;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class GatewayTest {

    private final ObjectMapper json = new ObjectMapper();

    @Test
    void routesToTheOwningProviderAndReturnsItsResultVerbatim() {
        JsonNode canned = json.createObjectNode().put("marker", "from-provider");
        Gateway gateway = new Gateway(List.of(new FakeProvider("search_symbols", canned)),
                new SimpleMeterRegistry(), false);

        JsonNode out = gateway.callTool("search_symbols", json.createObjectNode());

        assertThat(out).isEqualTo(canned);
    }

    @Test
    void recordsPerCallTelemetryWhenEnabled() {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        Gateway gateway = new Gateway(List.of(new FakeProvider("outline", json.createObjectNode())),
                metrics, true);

        gateway.callTool("outline", json.createObjectNode());

        assertThat(metrics.find("gateway.calls").tags("provider", "fake", "tool", "outline", "outcome", "ok")
                .timer()).isNotNull();
    }

    @Test
    void recordsErrorOutcomeAndPropagatesWhenProviderThrows() {
        SimpleMeterRegistry metrics = new SimpleMeterRegistry();
        Gateway gateway = new Gateway(List.of(new ThrowingProvider()), metrics, true);

        assertThatThrownBy(() -> gateway.callTool("boom", json.createObjectNode()))
                .isInstanceOf(RuntimeException.class);
        assertThat(metrics.find("gateway.calls").tags("outcome", "error").timer()).isNotNull();
    }

    @Test
    void unknownToolHasNoProvider() {
        Gateway gateway = new Gateway(List.of(new FakeProvider("outline", json.createObjectNode())),
                new SimpleMeterRegistry(), false);

        assertThatThrownBy(() -> gateway.callTool("nope", json.createObjectNode()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private record FakeProvider(String tool, JsonNode result) implements ContextProvider {
        @Override
        public String name() {
            return "fake";
        }

        @Override
        public CapabilityDescriptor describe() {
            return new CapabilityDescriptor("fake", EnumSet.of(Capability.STRUCTURE));
        }

        @Override
        public boolean handles(String t) {
            return tool.equals(t);
        }

        @Override
        public JsonNode toolsList() {
            return new ObjectMapper().createArrayNode();
        }

        @Override
        public JsonNode callTool(String t, JsonNode arguments) {
            return result;
        }
    }

    private static final class ThrowingProvider implements ContextProvider {
        @Override
        public String name() {
            return "throwing";
        }

        @Override
        public CapabilityDescriptor describe() {
            return new CapabilityDescriptor("throwing", EnumSet.noneOf(Capability.class));
        }

        @Override
        public boolean handles(String t) {
            return true;
        }

        @Override
        public JsonNode toolsList() {
            return new ObjectMapper().createArrayNode();
        }

        @Override
        public JsonNode callTool(String t, JsonNode arguments) {
            throw new IllegalStateException("boom");
        }
    }
}
