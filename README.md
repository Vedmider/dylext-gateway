# dylext-gateway

[![CI](https://github.com/Vedmider/dylext-gateway/actions/workflows/ci.yml/badge.svg)](https://github.com/Vedmider/dylext-gateway/actions/workflows/ci.yml)
[![Java 21](https://img.shields.io/badge/Java-21-orange.svg)](https://adoptium.net)
[![Spring Boot 3.3](https://img.shields.io/badge/Spring%20Boot-3.3-brightgreen.svg)](https://spring.io/projects/spring-boot)
[![License: BSL 1.1](https://img.shields.io/badge/license-BSL--1.1-blue.svg)](LICENSE)

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
The gateway is free for non-production use. If you want to run it in production — or want the whole
Dylext + Sestacon stack wired into your infrastructure without the lift — we offer commercial engagements:

- **Production/commercial license** — unlock production and competitive use before the Change Date.
- **Integration & setup** — deploy into your infrastructure (on-prem, VPC, or air-gapped) and wire the
  gateway + providers to your repositories, CI, IDEs, and AI agents / MCP clients.
- **Custom development** — new provider modules, routing/compose policies, and features prioritized for your roadmap.
- **Support & SLAs** — priority support, security reviews, upgrade assistance, and guaranteed response times.
- **Team enablement** — onboarding your engineers onto token-efficient, grounded AI workflows.

📩 Talk to us: **andrii.lisovets@gmail.com** — tell us your repos, scale, and the AI agents you run,
and we'll scope an integration.

## License
Source-available under the [Business Source License 1.1](LICENSE) — © 2026 Andrii Lisovets.
Non-production use is free; production use requires a commercial license until the Change Date
(2029-11-18 for v0.2.0), when each version converts to Apache-2.0.
