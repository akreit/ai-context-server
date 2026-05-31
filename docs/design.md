# Context as a Service (CaaS) — Design Document

## Context

A middleware server that decouples LLM consumers (chatbot UIs, CLI tools, other APIs) from LLM providers and tool infrastructure. The server accepts a user message, manages conversation sessions, activates the requested MCP tool servers, calls the appropriate LLM via sttp-ai, executes any tool calls the LLM makes, and returns the response. Clients never touch upstream LLM keys or MCP servers directly.

**RAG is intentionally omitted.** All data sources (Jira, GitHub, repo docs, support tickets) have or can have MCP servers. The LLM is better at deciding what to fetch and when than a pre-retrieval step. Tool results are cached per-session to avoid redundant MCP calls within a conversation.

**Confirmed design choices:**
- No RAG, no vector store, no embedding models
- Tool calling only — LLM decides reactively what to fetch via MCP tools
- `context_sets` reframed as "activate these MCP tool servers for this request" (enum: `jira`, `github`, `repo-docs`, `support-tickets`)
- Per-session tool result cache in-memory (avoids redundant MCP round-trips within a session; in-memory is sufficient — cache is ephemeral by nature and staleness on restart is acceptable)
- Stateful sessions — server holds conversation history in PostgreSQL (JSONB)
- API-key authentication only (single-tenant); straightforward path to multi-tenant later
- MCP client only (stdio transport; does not expose itself as MCP server)
- Official MCP Java SDK for MCP client (lighter than LangChain4j; no RAG baggage)
- sttp-ai for all LLM calls (non-negotiable)
- Scala 3.8.3 + tapir 1.13.19 + http4s Ember + Cats Effect (existing skeleton)

---

## Implementation Status

### Done
- [x] HTTP server — Ember / tapir wired in `Main.scala`
- [x] `POST /v1/context/completions` — `server/Endpoints.scala`
- [x] LLM Gateway trait + Anthropic Claude implementation — `service/LlmGateway.scala`, `service/ClaudeLlmGateway.scala`
- [x] Tool calling loop — embedded in `ClaudeLlmGateway.agentLoop`
- [x] MCP Registry (stdio clients, tool listing, tool execution) — `mcp/McpRegistry.scala`
- [x] MCP Tool Adapter (`McpSchema.Tool` → sttp-ai `Tool`) — `service/ToolAdapter.scala`
- [x] Tool result cache (in-memory, `IORef`-backed) — `cache/ToolResultCache.scala`
- [x] Configuration (PureConfig / HOCON) — `config/AppConfig.scala` (server, claude, mcp-servers)
- [x] Request / Response models — `model/` package
- [x] Context sets enum — `model/ContextSource.scala`
- [x] OpenAPI / Swagger UI — wired via `SwaggerInterpreter`
- [x] Basic OTel metrics (tapir request-level) — `Endpoints.metricsInterceptor`

### Not yet started
- [ ] API key authentication (interceptor + key service)
- [ ] Session management — PostgreSQL-backed conversation history, TTL sweep
- [ ] Prompt assembler — system message + history + user message construction
- [ ] History trimmer — JTokkit token-window trim before prompt assembly
- [ ] Model router — rules-based HOCON routing (model is currently fixed in config)
- [ ] Token budget / usage accounting — pre-flight check + post-call recording
- [ ] Detailed OTel metrics — `caas.tokens.prompt`, `caas.tool.calls_total`, etc.
- [ ] SSE streaming — `stream: true` path, fs2 → tapir `streamBody`
- [ ] Admin endpoints — session inspect/delete, MCP server list, model list
- [ ] Multi-provider LLM support — OpenAI and Ollama (Anthropic only today)
- [ ] Max-tool-rounds enforcement + `finish_reason: max_rounds_exceeded`

---

## System Overview

```
  Clients                  CaaS Server (Scala 3 / Cats Effect / http4s)
 ─────────                ─────────────────────────────────────────────────────
  Chatbot ──────────────▶ Auth Interceptor (API key check)
  Frontend ─────────────▶    │
  CLI ──────────────────▶    ▼
  Other API ────────────▶ Session Load ──▶ PostgreSQL (conversation history)
                              │
                              ▼
                         Prompt Assembly
                           (system msg + history + user message)
                              │
                              ▼
                         Model Router (rules-based, HOCON)
                              │
                              ▼
                         sttp-ai LLM Gateway
                           OpenAI │ Anthropic Claude │ Ollama
                              │
                         Tool Calling Loop ◀──────────────────────┐
                              │                                    │
                              ▼                                    │
                         Tool requested? ──yes──▶ MCP Registry    │
                              │                   (stdio clients)  │
                              │                        │           │
                              │                        ▼           │
                              │                   In-Memory Cache  │
                              │                   (check before    │
                              │                    calling MCP)    │
                              │                        │           │
                              │                   Execute MCP tool │
                              │                   Cache result     │
                              │                   Return to LLM ───┘
                              │ (finish_reason = stop)
                              ▼
                         Session Save ──▶ PostgreSQL
                              │
                              ▼
                         Usage Accounting ──▶ PostgreSQL + OTel metrics
                              │
                              ▼
                         CompletionResponse (JSON or SSE stream)
```

### Data flow — single non-streaming request

```
POST /v1/context/completions
  │
  ├─ 1. Auth: verify API key → 401 if invalid
  ├─ 2. Session load: fetch message history from PostgreSQL (empty if new session)
  ├─ 3. MCP activation: resolve context_sets → set of MCP tool specs to expose to LLM
  ├─ 4. Prompt assembly: system msg + history + tool specs + user message
  ├─ 5. Model routing: pick provider + model
  ├─ 6. LLM call (sttp-ai)
  │     ├─ finish_reason = stop → done
  │     └─ finish_reason = tool_calls →
  │           ├─ check in-memory cache for (session_id, tool, args)
  │           ├─ on miss: call MCP server, store result in cache
  │           └─ append tool result messages → loop to step 6
  ├─ 7. Session save: append exchange to PostgreSQL
  ├─ 8. Usage accounting: record token usage
  └─ 9. Return CompletionResponse
```

---

## API Design

### Versioning
All routes prefixed `/v1/`. Tapir's `endpoint.in("v1" / ...)`. Breaking changes → `/v2/`.
OpenAPI spec at `/docs` (Swagger UI already wired in existing skeleton).

### POST /v1/context/completions — Primary endpoint

**Request:**
```json
{
  "session_id": "sess-abc123",
  "message": "What open PRs are blocking the release?",
  "context_sets": ["github", "jira"],
  "model_hint": "claude-sonnet",
  "stream": false,
  "temperature": 0.7,
  "max_tool_rounds": 10
}
```

**`context_sets` enum — which MCP tool servers to activate:**

| Value | MCP server | Tools made available to LLM |
|---|---|---|
| `jira` | Jira MCP (stdio) | `search_issues`, `get_issue`, `list_projects`, ... |
| `github` | GitHub MCP (stdio) | `search_code`, `list_pull_requests`, `get_issue`, ... |
| `repo-docs` | Filesystem or docs MCP | `read_file`, `list_directory`, `search_files` |
| `support-tickets` | Zendesk/Freshdesk MCP | `search_tickets`, `get_ticket`, ... |

If `context_sets` is empty, the LLM gets no tools (plain chat completion).

**Response (non-streaming):**
```json
{
  "id": "cmpl-xyz",
  "session_id": "sess-abc123",
  "model": "claude-3-5-sonnet-20241022",
  "provider": "anthropic",
  "message": { "role": "assistant", "content": "The open PRs blocking release are..." },
  "tool_calls_made": [
    { "tool": "list_pull_requests", "source": "github", "cache_hit": false },
    { "tool": "search_issues",      "source": "jira",   "cache_hit": true  }
  ],
  "usage": {
    "prompt_tokens": 1820,
    "completion_tokens": 284,
    "total_tokens": 2104,
    "tool_rounds": 2
  },
  "finish_reason": "stop"
}
```

**Streaming response:** `Content-Type: text/event-stream`.
- Content delta events: `data: {"delta": {"content": "..."}}`
- Tool call events: `data: {"delta": {"tool_call": {"tool": "...", "status": "executing"}}}`
- Terminal: `data: [DONE]`

### Admin endpoints

```
GET    /v1/admin/sessions/{id}        — inspect session history
DELETE /v1/admin/sessions/{id}        — clear session + tool cache for session
GET    /v1/admin/mcp-servers          — list configured MCP servers and their tools
GET    /v1/admin/models               — list available model routes
```

Removed: `/v1/context/retrieve` and document ingest endpoints (no RAG, no vector store).

---

## Library Choices

### MCP client: Official MCP Java SDK

**Why not LangChain4j-mcp:** LangChain4j-mcp is a wrapper around the official SDK anyway, and it pulls in LangChain4j core — a large dependency we no longer need since there's no RAG. The official SDK is lighter and directly maintained by the MCP project.

```scala
"io.modelcontextprotocol.sdk" % "mcp"            % "0.9.0",
"io.modelcontextprotocol.sdk" % "mcp-spring-webflux" % "0.9.0",  // for HTTP MCP; stdio uses core only
```

The SDK provides synchronous and async facades. We use `McpSyncClient` wrapped in `IO.blocking`. At startup, one `McpSyncClient` per configured MCP server is initialized in the `Resource` graph and held in `McpRegistry`.

**Tool spec bridging:** MCP tool specs are JSON Schema objects (`McpSchema.Tool`). sttp-ai expects its own `ToolSpecification` type. A thin `McpToolAdapter` converts between them — this is manual but straightforward (name + description + JSON schema are present in both).

### sttp-ai (LLM calls)

All HTTP communication with LLM providers. fs2 streaming adapter pipes directly to tapir's `streamBody(Fs2Streams[IO])`. Handles tool calling protocol for both OpenAI (function calling) and Anthropic (tool use).

**Providers:** OpenAI, Anthropic Claude, Ollama. All credentials are server-side only.

### Session history: PostgreSQL

`sessions(session_id UUID PK, messages JSONB, created_at TIMESTAMPTZ, updated_at TIMESTAMPTZ)`

Messages stored as OpenAI-format `[{role, content}]` array (tool call and tool result messages included). Token-window trim applied before appending if history approaches model limit.

### Tool result cache: In-memory

Per-session cache to avoid redundant MCP calls within a conversation.

- **Key:** `(session_id, tool_name, sha256(canonicalArgs))`
- **Value:** tool result string (raw MCP response content)
- **Implementation:** `IORef[Map[CacheKey, String]]` per session, held in the session's fiber scope
- **Lifetime:** process lifetime — cache is intentionally ephemeral; a restart serving fresh MCP data is preferable to serving stale cached results

**Why not PostgreSQL or Redis:** The cache's purpose is intra-session deduplication within a single conversation. Tool results (Jira issues, GitHub PRs) change over time, so persisting them across restarts risks serving stale data to the LLM. In-memory naturally expires with the process, which aligns with "data was fresh when this conversation was active." The `ToolResultCache` trait keeps the door open to swap implementations if cross-process sharing is ever needed.

### Token counting: JTokkit (standalone)

JTokkit is available on Maven Central independently (not just via LangChain4j). Used for history token-window trimming and token budget pre-checks.

```scala
"com.knuddels" % "jtokkit" % "1.1.0"
```

For Claude: `cl100k_base` as a ≤5% estimate; authoritative count comes from Anthropic `usage` in the response.

### Streaming: SSE over HTTP

Unidirectional, works through proxies, maps directly to LLM streaming. WebSocket deferred until mid-stream interrupts are needed.

---

## Session Management

### Lifecycle
- Client sends `session_id` (UUID). If absent, server creates one and returns it.
- PostgreSQL `sessions` table stores the full `[{role, content}]` message list as JSONB.
- TTL: configurable (default 24h). Background fiber (`IO.sleep` loop) sweeps expired rows.

### History trimming
Before assembling the prompt, count tokens in history with JTokkit. If `historyTokens > model.maxInputTokens - systemTokens - reserveForCompletion`, drop oldest messages (preserving the system message) until it fits. No LLM-based summarization in v1.

### Tool call messages in history
Tool calls and their results are appended to session history in OpenAI format:
```json
{ "role": "assistant", "tool_calls": [{ "id": "tc-1", "function": { "name": "search_issues", "arguments": "..." } }] }
{ "role": "tool", "tool_call_id": "tc-1", "content": "Issue PROJ-421: ..." }
```
This lets subsequent turns in the same session reference previous tool results without re-fetching.

---

## Tool Calling and MCP Integration

### MCP Registry

At startup, `McpRegistry` reads the configured MCP servers from HOCON and initializes one `McpSyncClient` per server:

```scala
def buildMcpRegistry(config: CaasConfig): Resource[IO, McpRegistry] =
  config.mcpServers.toList.traverse { (name, cfg) =>
    Resource.make(
      IO.blocking(McpClient.sync(new StdioClientTransport(cfg.command, cfg.args)))
    )(client => IO.blocking(client.close()))
      .map(name -> _)
  }.map(pairs => McpRegistry(pairs.toMap))
```

`McpRegistry` exposes:
- `toolSpecs(contextSets): List[ToolSpec]` — all tool specs from the requested servers, converted to sttp-ai format
- `execute(sessionId, toolName, args): IO[String]` — checks in-memory cache first, calls MCP on miss, stores result

### Tool calling loop

```scala
def completionLoop(
  messages: List[ChatMessage],
  tools: List[ToolSpec],
  maxRounds: Int
): IO[CompletionResult] =
  llmGateway.complete(messages, tools).flatMap:
    case r if r.finishReason == ToolCalls && maxRounds > 0 =>
      r.toolCalls
        .traverse(call => mcpRegistry.execute(sessionId, call.name, call.arguments)
          .map(result => toolResultMessage(call.id, result)))
        .flatMap(toolMsgs =>
          completionLoop(
            messages ++ List(r.asAssistantMessage) ++ toolMsgs,
            tools,
            maxRounds - 1
          ))
    case r => IO.pure(r)
```

### MCP tool adapter

```scala
object McpToolAdapter:
  def toSttpAiTool(mcpTool: McpSchema.Tool): ToolSpec =
    ToolSpec(
      name        = mcpTool.name,
      description = mcpTool.description,
      inputSchema = mcpTool.inputSchema  // already JSON Schema — direct pass-through
    )
```

---

## Model Routing

Rules-based, first match wins, configured in HOCON:

```hocon
model-routing {
  default = "claude-3-5-haiku-20241022"
  rules = [
    { when.tools-available = true,       use = "claude-3-5-sonnet-20241022" }
    { when.estimated-tokens-gt = 50000,  use = "gpt-4o" }
    { when.model-hint = "opus",          use = "claude-opus-4-5" }
  ]
}
```

`tools-available = true` fires whenever `context_sets` is non-empty (which means tool specs will be sent to the LLM). Model ID prefix determines provider.

---

## Authentication

API key only. Keys stored hashed (SHA-256) in PostgreSQL `api_keys(key_hash, created_at)` or as environment variables for initial simplicity.

Tapir `CustomInterceptor` (runs before all endpoint logic):
```scala
request.header("Authorization") match
  case Some(s"Bearer $key") => apiKeyService.verify(key)  // IO[Either[AuthError, Unit]]
  case _                    => IO.pure(Left(MissingKeyError))
```

**Future multi-tenant path:** replace `Unit` with a `Principal` carrying per-tenant allowed `context_sets`, model restrictions, and token budget. No other code changes needed.

---

## Token Budget and Usage Accounting

### Pre-flight
Check remaining quota against `user_budgets` table. Unlimited by default in single-tenant mode.

### Post-call
```sql
UPDATE user_budgets
SET used_tokens = used_tokens + $total, updated_at = now()
WHERE api_key_hash = $hash
```

### OTel metrics (existing `metricsInterceptor` in `Main.scala`)
- `caas.tokens.prompt` — counter, tagged by model, provider
- `caas.tokens.completion` — counter
- `caas.tool.calls_total` — counter, tagged by tool name, source (mcp server), cache_hit
- `caas.tool.cache_hit_rate` — derived from above
- `caas.request.latency_ms` — histogram
- `caas.tool.round_trips` — histogram (how many tool loops per request)

---

## Configuration (HOCON via PureConfig)

```hocon
caas {
  server { port = 8080, host = "0.0.0.0" }

  auth {
    api-keys = [${API_KEY_1}]
  }

  database {
    url      = ${POSTGRES_URL}
    username = ${POSTGRES_USER}
    password = ${POSTGRES_PASSWORD}
    pool-size = 10
  }

  llm-providers {
    openai    { api-key = ${OPENAI_API_KEY} }
    anthropic { api-key = ${ANTHROPIC_API_KEY} }
    ollama    { base-url = "http://localhost:11434" }
  }

  model-routing {
    default = "claude-3-5-haiku-20241022"
    rules = [
      { when.tools-available = true,      use = "claude-3-5-sonnet-20241022" }
      { when.estimated-tokens-gt = 50000, use = "gpt-4o" }
    ]
  }

  mcp-servers {
    jira {
      command = "/usr/local/bin/mcp-jira"
      args    = []
    }
    github {
      command = "/usr/local/bin/mcp-github"
      args    = []
    }
    repo-docs {
      command = "/usr/local/bin/mcp-filesystem"
      args    = ["--root", "/data/docs"]
    }
    support-tickets {
      command = "/usr/local/bin/mcp-zendesk"
      args    = []
    }
  }

  sessions {
    ttl-hours = 24
    max-history-tokens = 8000
  }

  token-budgets {
    reserve-for-completion = 4096
    unlimited = true
  }

  tool-calling {
    max-rounds = 10
    timeout-per-tool-seconds = 10
  }
}
```

---

## Package Structure

> **Note:** The actual source tree diverges from the design in several places — `api/` split into `server/` and `model/`; `llm/` became `service/`; tool adapter and executor live in `service/` and `mcp/` respectively. Annotations below reflect current state.

```
com.github.akreit
├── Main.scala                        ✅ IOApp, Resource wiring, Ember server
│
├── server/                           (designed as api/)
│   └── Endpoints.scala               ✅ tapir endpoint definitions
│
├── model/                            (designed as api/model/)
│   ├── ClientRequest.scala           ✅ session_id, message, context_sets, model_hint, stream, ...
│   ├── CompletionResponse.scala      ✅ id, session_id, model, message, tool_calls_made, usage
│   ├── ContextSource.scala           ✅ context_sets enum (designed as part of ClientRequest)
│   ├── ErrorResponse.scala           ✅ error response body
│   ├── LlmError.scala                ✅ typed LLM error ADT
│   ├── ToolCallRecord.scala          ❌ (ToolCallMade embedded in CompletionResponse today)
│   ├── StreamEvent.scala             ❌ SSE delta events — not yet implemented
│   └── TokenUsage.scala              ❌ (Usage embedded in CompletionResponse today)
│
├── api/codec/
│   └── JsonCodecs.scala              ❌ jsoniter-scala derivations (using auto-derivation today)
│
├── auth/
│   ├── AuthService.scala             ❌ trait: verify(key) → IO[Either[AuthError, Unit]]
│   ├── ApiKeyAuthService.scala       ❌ SHA-256 hash lookup
│   └── AuthInterceptor.scala         ❌ tapir CustomInterceptor
│
├── config/
│   └── AppConfig.scala               ✅ PureConfig ADTs (server, claude, mcp-servers only)
│                                        ❌ missing: database, llm-providers, model-routing,
│                                           sessions, token-budgets, tool-calling sections
│
├── session/
│   ├── SessionService.scala          ❌ trait: load / save / clear
│   └── PostgresSessionService.scala  ❌ JSONB, TTL sweep fiber
│
├── mcp/
│   └── McpRegistry.scala             ✅ Map[McpServerName, McpSyncClient]; startup + shutdown,
│                                        tool listing, tool execution
│
├── service/                          (designed as mcp/ + llm/)
│   ├── LlmGateway.scala             ✅ trait: complete / stream
│   ├── ClaudeLlmGateway.scala       ✅ sttp-ai Anthropic; tool calling loop embedded here
│   └── ToolAdapter.scala            ✅ McpSchema.Tool → sttp-ai Tool (designed as McpToolAdapter)
│
├── cache/
│   └── ToolResultCache.scala         ✅ trait + IORef-backed in-memory implementation
│
├── prompt/
│   ├── PromptAssembler.scala         ❌ builds Seq[ChatMessage]: system + history + user msg
│   └── HistoryTrimmer.scala          ❌ JTokkit token-window trim of session history
│
├── model/ (routing)
│   ├── ModelRouter.scala             ❌ trait + RulesBasedModelRouter
│   └── ModelRoute.scala              ❌
│
├── budget/
│   ├── BudgetService.scala           ❌ trait: check / record
│   └── PostgresBudgetService.scala   ❌
│
├── telemetry/
│   └── Metrics.scala                 ❌ detailed OTel counter/histogram definitions
│                                        (basic tapir-level metrics exist in Endpoints.scala)
│
└── utils/
    └── CatsLogger.scala              ✅ log4cats helper
```

---

## Dependency Additions to build.sbt

```scala
// MCP client (official SDK)
"io.modelcontextprotocol.sdk" % "mcp"            % "0.9.0",

// sttp-ai (OpenAI + Anthropic + fs2 streaming)
"com.softwaremill.sttp.ai" %% "sttp-openai"       % "0.4.0",

// PostgreSQL + connection pool
"org.postgresql"  % "postgresql"                   % "42.7.4",
"com.zaxxer"      % "HikariCP"                     % "5.1.0",

// Token counting
"com.knuddels"   % "jtokkit"                        % "1.1.0",

// Config
"com.github.pureconfig" %% "pureconfig-core"        % "0.17.7",
```

Removed vs previous: `langchain4j`, `langchain4j-pgvector`, `langchain4j-cohere`, `langchain4j-mcp`, `langchain4j-open-ai`.

---

## Open Questions / Risks

1. **Official MCP Java SDK + stdio on JVM.** Verify the SDK's `StdioClientTransport` correctly manages subprocess lifecycle on JVM (process spawn, stdout/stdin piping, cleanup on `Resource` release). This is the highest-risk unknown — prototype before anything else.

2. **sttp-ai tool spec format.** Confirm the exact type sttp-ai uses for tool definitions when calling Anthropic vs OpenAI — the provider wire formats differ (Anthropic uses `input_schema`, OpenAI uses `parameters`). sttp-ai should abstract this, but verify. The `McpToolAdapter` needs to know what type to produce.

3. **Tool result cache is in-memory only.** Cache is lost on restart; MCP re-call is the fallback. This is intentional — tool results (Jira, GitHub) change over time and serving stale cached data post-restart is worse than a fresh MCP call. If horizontal scaling or cross-process cache sharing is ever needed, the `ToolResultCache` trait allows swapping in a Redis implementation without touching call sites.

4. **MCP tool result size.** Some MCP tools (e.g. `get_issue` with full description, `read_file` for large files) can return large payloads. These count against the context window. Add a `max-tool-result-chars` config option that truncates oversized results with a `[truncated]` suffix before appending to messages.

5. **Rate limiting.** API key auth doesn't rate-limit. Add per-key sliding-window request counter (in-memory `AtomicLong` + background reset fiber, or PostgreSQL). Without this, a single key can exhaust upstream LLM quota rapidly via many parallel requests.

6. **Session history + tool messages grow large.** A session with many tool-calling turns accumulates tool call + tool result message pairs. The history trimmer must handle these (they can't be arbitrarily dropped — a tool result without its paired tool call breaks the message structure). Trimming strategy: drop oldest complete exchange pairs (user + assistant + all tool calls/results for that turn) as a unit.

---

## Verification Plan

- [ ] **Unit tests: `RulesBasedModelRouter`** — rule evaluation logic
- [ ] **Unit tests: `HistoryTrimmer`** — token math, pair integrity (requires JTokkit)
- [x] **Unit tests: `McpToolAdapter`** — schema conversion (`ToolAdapterSpec` exists)
- [ ] **Integration tests: PostgreSQL** — Testcontainers; full pipeline with mock MCP server (spawn a simple stdio process that echoes fixed responses); assert session history accumulated correctly
- [ ] **Integration tests: cache** — same query twice in same session; second request's `tool_calls_made[].cache_hit` must be `true`
- [ ] **Streaming test:** `curl -N -H "Authorization: Bearer $KEY" -d '{"message":"...","context_sets":["github"],"stream":true}' http://localhost:8080/v1/context/completions` — observe SSE events including tool-call status events
- [ ] **Max rounds test:** configure `max-tool-rounds = 2`, send a query that would require 5 tool loops — verify `finish_reason: max_rounds_exceeded` rather than hanging
- [ ] **Auth test:** missing or invalid `Authorization` header → 401