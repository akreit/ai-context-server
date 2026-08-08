# 3 - Switch to chimp's MCP client

## Problem

We currently depend on the MCP Java SDK (`io.modelcontextprotocol.sdk:mcp`)
for `McpRegistry`. Its `McpSyncClient` is Java-flavored: tool schemas come
back as raw `java.util.Map[String, Object]`, and tool arguments have to be
converted from `io.circe.Json` into Java objects (`ToolAdapter.ujsonToJavaArg`)
before being passed to `callTool`. This mapping layer exists solely to bridge
Java collections into the Scala/circe-based rest of the stack (sttp-ai).

[Chimp](https://github.com/softwaremill/chimp) is a Scala 3, Tapir/sttp-based
MCP client/server library. `chimp-client_3` reached `0.5.0` on Maven Central
on 2026-08-04, and now includes `ClientStdioTransport`, which is what
`McpRegistry` needs (issue #3 was blocked on this being available).

## Approach

- **Dependency**: replace `io.modelcontextprotocol.sdk % mcp % 2.0.0` in
  `build.sbt` with `com.softwaremill.chimp %% chimp-client % 0.5.0`.

- **`McpRegistry`**: replace `Map[String, McpSyncClient]` with
  `Map[String, McpClient[Identity]]` (`sttp.shared.Identity`, `chimp.client.McpClient`).
  `ClientStdioTransport` is synchronous (`Identity`-based) just like the Java
  SDK's sync client, so the existing pattern of wrapping calls in
  `IO.blocking` carries over unchanged:
  - `clientResource`: build `ClientStdioTransport(cfg.command :: cfg.args, cfg.env)`
    and `McpClient[Identity](transport, Implementation("ai-context-server", "0.1.0"))`
    inside `IO.blocking`; close via `client.close()` in the release action.
  - `toolSpecs`: `client.listTools().tools` (`List[chimp.protocol.ToolDefinition]`)
    instead of `client.listTools().tools.asScala.toList`.
  - `execute`: `client.callTool(toolName, argumentsJson)` where
    `argumentsJson: Json` is built directly from `Map[String, Json]` — see
    below. Join `CallToolResult.content` by collecting `ToolContent.Text`.

- **`ToolAdapter`**: this is where most of the simplification lands, because
  chimp's `ToolDefinition.inputSchema` is already `io.circe.Json`, not a raw
  Java map, and sttp-ai's `ContentBlock.ToolUse.input` is already
  `Map[String, io.circe.Json]`:
  - `ujsonToJavaArg` is deleted outright. Tool call arguments go straight
    from `Map[String, Json]` to `Json.obj(args.toSeq*)` — no Java bridging
    needed at all.
  - `fromJavaMcpTool` becomes `fromChimpTool(tool: ToolDefinition): Tool`,
    reading `type`/`properties`/`required` off `tool.inputSchema` with circe's
    `.hcursor`/`.downField` instead of `java.util.Map` lookups.
    `extractPropertySchema` similarly switches to a circe `Json` case, using
    `.asString`/`.asArray` instead of `java.util.Map`/`java.util.List` casts.

- **Tests**: `ClaudeLlmGatewaySpec` builds a real `McpSchema.Tool` today to
  exercise `toolSpecs → ToolAdapter → agent loop → execute` end-to-end; swap
  that for a real `chimp.protocol.ToolDefinition` with a circe `Json`
  `inputSchema`. Behavior asserted by the spec (args passed through with
  correct types, final response returned) is unaffected — args are circe
  `Json` on both sides of the call now, so if anything the assertions get
  simpler (no more `java.lang.Double` boxing check).

- **Config**: `McpServerConfig` (`command`, `args`, `env`) is unchanged;
  `cfg.command :: cfg.args` adapts it to chimp's single `List[String]`
  command shape.

No behavior change is intended for callers of `McpRegistry` (`ClaudeLlmGateway`) — only its internals and `ToolAdapter` are touched.

## Rejected alternatives

- **Keep the MCP Java SDK.** Rejected: it's the reason issue #3 exists — the
  Java-collections bridging in `ToolAdapter` is pure incidental complexity
  now that a native Scala/circe MCP client is available.
- **Wait for a chimp `cats-effect` integration module before switching.**
  Rejected: chimp's `ClientStdioTransport` is synchronous (`Identity`), same
  as the Java SDK's `McpSyncClient` we use today. We already wrap the sync
  Java client in `IO.blocking`; doing the same for chimp's sync client is no
  worse, and there's no need to wait on a module that doesn't affect the
  outcome here.
- **Use chimp's HTTP transport instead of stdio.** Rejected: out of scope —
  all currently configured MCP servers are local subprocesses
  (`McpServerConfig.command`/`args`/`env`), matching stdio transport. Switching
  transport is an unrelated, larger change.
