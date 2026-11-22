# Tools

All tools are reached through the **dylext-gateway** at
`http://localhost:18070/mcp` (JSON-RPC `tools/call`). Sestacon is reachable
through the gateway's `answer` federated tool, or directly at
`http://localhost:18080/api/retrieve`.

Register the gateway in your MCP client config:

```json
{
  "mcpServers": {
    "dylext-gateway": {
      "url": "http://localhost:18070/mcp",
      "headers": { "X-Roles": "engineering" }
    }
  }
}
