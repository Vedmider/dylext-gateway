# Code Review Agent

A **skill package** that teaches an AI to perform code reviews against the
`code-review-criteria` specification, using the Dylext + Sestacon stack via
`dylext-gateway` as its tool surface.

No service, no orchestrator, no new endpoint. The AI is the agent.

## What it contains

| File | Purpose |
|---|---|
| `agent.md` | Identity and hard rules (evidence, severity, merge, verdict) |
| `workflow.md` | The 9-step review procedure |
| `tools.md` | Gateway tools and how to call them |
| `output.md` | Output JSON schema and invariants |
| `spec/code-review-criteria*.md` | The criteria catalog (v1.6.0) |

## Prerequisites

- `dylext-gateway` running (default `http://localhost:18070/mcp`)
- `dylext` provider running (for code + git tools)
- `sestacon` provider running (for docs / ADRs; optional if you only need
  code review)
- An MCP-capable AI (Cursor, Claude Code, Kiro, Cline, continue.dev, or a
  custom client using the same gateway)

## Install

### Claude Code

Copy `agent.md`, `workflow.md`, `tools.md`, `output.md`, and `spec/` into
`~/.claude/skills/code-review/` (or the project's `.claude/skills/` if you
want it per-repo).

Or append the four md files to `CLAUDE.md`, and add `spec/` as a referenced
directory.

### Cursor

Place `agent.md`, `workflow.md`, `tools.md`, `output.md` as
`.cursor/rules/code-review.md` (concatenate in that order). Reference
`spec/` from the same rule.

Add the gateway to Cursor's MCP config:

```json
{
  "mcpServers": {
    "dylext-gateway": { "url": "http://localhost:18070/mcp" }
  }
}
