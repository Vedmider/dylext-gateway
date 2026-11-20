# Contributing to dylext-gateway

Thanks for helping improve the gateway. This repo favours small, test-first changes.

## Prerequisites

- JDK 21

## Build & test

```bash
mvn -B clean test   # unit + component tests
```

The gateway is stateless and routes MCP tool calls / REST requests to the provider that owns
them (Dylext for `/mcp`, Sestacon for `/api`). Test routing and error-passthrough behaviour
with the providers mocked; no external services are required for the unit suite.

## Pull requests

1. Write or update a test first; watch it fail, then make it pass.
2. Keep the change focused; match existing style and the minimal-comment convention.
3. Run `mvn -B clean test` before pushing. CI must be green.

## Reporting issues

Use the issue templates. For anything security-sensitive, see [SECURITY.md](SECURITY.md).
