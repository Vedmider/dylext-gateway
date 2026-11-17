package io.github.vedmider.gateway.domain.port.out;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.vedmider.gateway.domain.model.CapabilityDescriptor;

/**
 * A backend the gateway can route an MCP tool call to (Dylext, Sestacon, code-memory, …). The gateway
 * never implements domain logic — it only picks a provider and forwards. Providers own the work.
 */
public interface ContextProvider {

    String name();

    CapabilityDescriptor describe();

    /** True if this provider serves the given MCP tool. */
    boolean handles(String tool);

    /** The provider's advertised tools (the {@code tools} array of an MCP {@code tools/list}). */
    JsonNode toolsList();

    /** Forward a {@code tools/call} and return the MCP {@code result} object verbatim. */
    JsonNode callTool(String tool, JsonNode arguments);
}
