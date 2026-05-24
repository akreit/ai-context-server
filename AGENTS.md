# General instructions

* ALWAYS use Metals MCP tool via curl (see below) to compile and run tests instead of relying on bash commands
* If MCP tools are not available report that to the user
* after adding a dependency to build.sbt, ALWAYS run the `import-build tool
* to look up a dependency or the latest version, use the `find-dep` tool
* to look up the API of a class, use the `inspect` tool
* use sbt --client instead of sbt to connect to a running sbt server for faster execution
* to verify that the app starts use sbt run, WITHOUT --client, as it prevents interrupting the process
* before committing, ALWAYS format all changed Scala files using `sbt scalafmtAll` & `sbt scalafmtTestAll`
* NEVER use non-local returns

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
grep -i "^Mcp-Session-Id:" | awk '{print $2}' | tr -d '\r')
```

# Send initialized notification

```bash
curl -s $METALS_MCP_HOST/mcp -X POST \
-H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
-H "mcp-session-id: $SESSION_ID" \
-d '{"jsonrpc":"2.0","method":"notifications/initialized"}' > /dev/null
```

# Use the server

> Commands below are examples. Adapt arguments (e.g. `fqcn`, `fileInFocus`, `testClass`) to the actual symbol or file you are working with.
> Parse the JSON response via the following command piped after each curl:

```bash
`python3 -c "import sys,json,re; m=re.search(r'data: (\{.*\})', sys.stdin.read(), re.DOTALL); print(m and json.loads(m.group(1))['result']['content'][0]['text'])"`
```

## compile-full

```bash
curl -s $METALS_MCP_HOST/mcp -X POST \
-H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
-H "mcp-session-id: $SESSION_ID" \
-d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"compile-full","arguments":{}}}'
```

## test (by class)

```bash
curl -s $METALS_MCP_HOST/mcp -X POST \
-H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
-H "mcp-session-id: $SESSION_ID" \
-d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"test","arguments":{"testClass":"com.github.akreit.service.ToolAdapterSpec"}}}'
```

## inspect (type/symbol)

```bash
curl -s $METALS_MCP_HOST/mcp -X POST \
-H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
-H "mcp-session-id: $SESSION_ID" \
-d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"inspect","arguments":{"fqcn":"com.github.akreit.service.LlmGateway"}}}'
```

## get-source

```bash
curl -s $METALS_MCP_HOST/mcp -X POST \
-H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
-H "mcp-session-id: $SESSION_ID" \
-d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get-source","arguments":{"fqcn":"com.github.akreit.service.ClaudeLlmGateway"}}}'
```

## get-usages

```bash
curl -s $METALS_MCP_HOST/mcp -X POST \
-H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
-H "mcp-session-id: $SESSION_ID" \
-d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"get-usages","arguments":{"fqcn":"com.github.akreit.service.LlmGateway","fileInFocus":"src/main/scala/com/github/akreit/service/ClaudeLlmGateway.scala"}}}'
```

## glob-search

```bash
curl -s $METALS_MCP_HOST/mcp -X POST \
-H "Content-Type: application/json" -H "Accept: application/json, text/event-stream" \
-H "mcp-session-id: $SESSION_ID" \
-d '{"jsonrpc":"2.0","id":2,"method":"tools/call","params":{"name":"glob-search","arguments":{"query":"LlmGateway","fileInFocus":"src/main/scala/com/github/akreit/service/ClaudeLlmGateway.scala"}}}'
```
