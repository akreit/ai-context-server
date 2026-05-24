# Compile & test Scala project with Metals MCP

* For reference, see: https://github.com/NovaMage/agents-metals-direct-lsp
* intermediate solution until this issue is implemented: https://github.com/anthropics/claude-code/issues/45132

# Start (once per session, runs in background)

```bash
METALS_MCP_HOST=http://localhost:55453
metals-mcp --workspace ~/dev/ai-context-server --port 55453
```

# Initialize MCP session

```bash
SESSION_ID=$(curl -s --dump-header - --output /dev/null $METALS_MCP_HOST/mcp -X POST \
-H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
-d '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2024-11-05","capabilities":{},"clientInfo":{"name":"agent","version":"1.0"}}}' | \
awk 'BEGIN{IGNORECASE=1} $0 ~ /^mcp-session-id[[:space:]]*:/ {sub(/^[^:]*:[[:space:]]*/, "", $0); sub(/\r$/, "", $0); print; exit}')
```

# Send initialized notification

```bash
curl -s $METALS_MCP_HOST/mcp -X POST \
-H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
-H "mcp-session-id: $SESSION_ID" \
-d '{"jsonrpc":"2.0","method":"notifications/initialized"}' > /dev/null
```

# Query (e.g., get-usages)

```bash
curl -s $METALS_MCP_HOST/mcp -X POST \
-H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
-H "mcp-session-id: $SESSION_ID" \
-d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get-usages","arguments":{"fqcn":"com.example.MyClass.myField","module":"core"}}}'
```
