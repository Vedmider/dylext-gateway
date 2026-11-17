# dylext-gateway

A **thin, stateless MCP front door** for the context ecosystem. Agents talk to the gateway; it routes
each tool call to the provider that owns it — **Dylext** (exact code + git), **Sestacon** (doc RAG),
**code-memory** (fuzzy recall) — records per-call telemetry, and returns the provider's result
verbatim. It holds **no state** (any cache is an external tier) and implements **no domain logic** —
providers own the work. This is the "extract the router from the code provider" move: Dylext becomes a
peer provider instead of the federation chokepoint.

Status: Phase 1 (seam + telemetry). Dylext is the sole backend and already federates
explain/recall/answer, so the gateway currently proxies every tool to it. Later increments add the
Sestacon / code-memory providers directly and the epoch + capability-cache layers.

## Design invariants (do not break)
- **Thin:** route, meter, (later) cache/ground — never re-implement provider domain logic.
- **Stateless:** no DB; horizontally scalable; cache lives in an external tier.
- **Additive:** the MCP tool surface mirrors the providers'; existing agents keep working.

## Run
```bash
mvn -B spring-boot:run        # starts on :18070 (Dylext expected on :18090)
# point an MCP client / dx-style caller at POST http://localhost:18070/mcp
```
Config: `gateway.providers.dylext-url` (default `http://localhost:18090`), `gateway.telemetry-enabled`.
Metrics: `GET /actuator/prometheus` (`gateway.calls` timer tagged provider/tool/outcome).

Security: bind loopback-only in deployment (add an access filter, as Dylext does for `/mcp`).

## Enterprise setup & support
Running this in production or wiring it into your stack? Commercial licensing, integration, support and
SLAs: **andrii.lisovets@gmail.com**.

## License
Source-available under the [Business Source License 1.1](LICENSE) — © 2026 Andrii Lisovets.
Non-production use is free; production use requires a commercial license until the Change Date
(2029-11-17 for v0.1.0), when each version converts to Apache-2.0.
