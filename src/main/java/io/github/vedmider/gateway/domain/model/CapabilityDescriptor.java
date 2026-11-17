package io.github.vedmider.gateway.domain.model;

import java.util.Set;

/** What one provider advertises: its name and the capabilities it serves. */
public record CapabilityDescriptor(String provider, Set<Capability> capabilities) {

    public CapabilityDescriptor {
        capabilities = Set.copyOf(capabilities);
    }
}
