# Security Policy

## Reporting a vulnerability

Please report security issues privately via
[GitHub Security Advisories](https://github.com/Vedmider/dylext-gateway/security/advisories/new)
rather than opening a public issue. You will get an acknowledgement within a few days.

## Supported versions

The latest release on `main` receives security fixes. Older versions are not maintained.

## Scope notes

The gateway is a stateless front door that proxies to Dylext (`/mcp`) and Sestacon (`/api`).
It does not authenticate callers itself — when exposing it beyond localhost, place it behind
your own authentication/authorization and restrict which providers it can reach.
