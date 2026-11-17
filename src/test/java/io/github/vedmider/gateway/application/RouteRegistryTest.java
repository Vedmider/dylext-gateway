package io.github.vedmider.gateway.application;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class RouteRegistryTest {

    private final RouteRegistry routes =
            new RouteRegistry("http://dylext:18090", "http://sestacon:18080/");

    @Test
    void routesMcpToDylextAndApiToSestacon() {
        assertThat(routes.match("/mcp").orElseThrow().name()).isEqualTo("dylext");
        assertThat(routes.match("/api/ingest").orElseThrow().name()).isEqualTo("sestacon");
        assertThat(routes.match("/api/retrieve").orElseThrow().name()).isEqualTo("sestacon");
    }

    @Test
    void trailingSlashInUpstreamIsNormalised() {
        assertThat(routes.match("/api/ingest").orElseThrow().upstreamBaseUrl()).isEqualTo("http://sestacon:18080");
    }

    @Test
    void unknownPathHasNoModule() {
        assertThat(routes.match("/actuator/health")).isEmpty();
        assertThat(routes.match("/mcpother")).isEmpty();
    }
}
