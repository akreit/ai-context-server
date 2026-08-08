package com.github.akreit.claude

import cats.effect.IO
import cats.effect.Resource
import cats.effect.unsafe.implicits.global
import chimp.protocol.ToolDefinition
import com.github.akreit.mcp.McpRegistry
import com.github.akreit.model.ClientRequest
import com.github.akreit.model.ContextSource
import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.ai.claude.ClaudeClient
import sttp.ai.claude.config.ClaudeConfig
import sttp.ai.claude.models.ContentBlock
import sttp.ai.claude.models.Usage as ClaudeUsage
import sttp.ai.claude.responses.MessageResponse
import sttp.client4.testing.BackendStub
import sttp.client4.testing.ResponseStub
import sttp.tapir.integ.cats.effect.CatsMonadError

/** Integration-style tests for [[ClaudeLlmGateway]] that exercise the full
  * agent loop without hitting the network.
  *
  * The sttp backend is replaced with a [[sttp.client4.testing.BackendStub]]
  * that cycles through pre-baked [[MessageResponse]] values via
  * `thenRespondCyclic`. `ResponseStub.exact` bypasses response deserialization
  * and returns the typed value directly, so no JSON round-trip occurs.
  *
  * A real [[ToolDefinition]] is constructed and returned from the stubbed
  * [[McpRegistry]], so the full pipeline — `toolSpecs` →
  * `ToolAdapter.fromChimpTool` → agent loop → `execute` — is exercised
  * end-to-end. The stub `McpRegistry` captures the server name, tool name, and
  * arguments, allowing assertions at every stage of the flow.
  */
class ClaudeLlmGatewaySpec extends AnyFlatSpec with Matchers:

  // A real MCP tool definition with two input parameters
  private val searchTool: ToolDefinition =
    ToolDefinition(
      name = "search",
      description = Some("Search GitHub for PRs and issues"),
      inputSchema = Json.obj(
        "type" -> Json.fromString("object"),
        "properties" -> Json.obj(
          "query" -> Json.obj(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString("GitHub search query")
          ),
          "limit" -> Json.obj(
            "type" -> Json.fromString("number"),
            "description" -> Json.fromString("Max results to return")
          )
        ),
        "required" -> Json.arr(Json.fromString("query"))
      )
    )

  // First Claude response: requests a tool call
  private val toolUseResponse = MessageResponse(
    id = "msg-1",
    `type` = "message",
    role = "assistant",
    content = List(
      ContentBlock.ToolUse(
        id = "tool-1",
        name = "search",
        input = Map(
          "query" -> Json.fromString("user:akreit"),
          "limit" -> Json.fromDoubleOrNull(5)
        )
      )
    ),
    model = "claude-sonnet-4-20250514",
    stopReason = Some("tool_use"),
    stopSequence = None,
    usage = ClaudeUsage(inputTokens = 10, outputTokens = 5)
  )

  // Second Claude response: final answer after receiving the tool result
  private val finalResponse = MessageResponse(
    id = "msg-2",
    `type` = "message",
    role = "assistant",
    content = List(ContentBlock.Text("Search complete")),
    model = "claude-sonnet-4-20250514",
    stopReason = Some("end_turn"),
    stopSequence = None,
    usage = ClaudeUsage(inputTokens = 15, outputTokens = 8)
  )

  private def makeGateway(stubRegistry: McpRegistry): ClaudeLlmGateway =
    val stubBackend = BackendStub[IO](new CatsMonadError[IO]()).whenAnyRequest
      .thenRespondCyclic(
        ResponseStub.exact(Right(toolUseResponse)),
        ResponseStub.exact(Right(finalResponse))
      )
    ClaudeLlmGateway(
      client = ClaudeClient(ClaudeConfig(apiKey = "test-key")),
      model = "claude-sonnet-4-20250514",
      maxTokens = 1024,
      systemPrompt = None,
      mcpRegistry = stubRegistry,
      backendResource = Resource.pure(stubBackend)
    )

  private val githubRequest = ClientRequest(
    userId = "u1",
    message = "Find open PRs",
    additionalSources = Some(List(ContextSource.GitHub)),
    timestamp = 0L
  )

  "ClaudeLlmGateway agent loop" should "pass tool-use args as a JSON object to McpRegistry" in {
    var capturedServer = ""
    var capturedTool = ""
    var capturedArgs: Json = Json.Null

    val stubRegistry = new McpRegistry(Map.empty):
      override def toolSpecs(
          serverNames: Option[List[String]]
      ): IO[Option[List[ToolDefinition]]] =
        IO.pure(Some(List(searchTool)))
      override def execute(
          serverName: String,
          toolName: String,
          arguments: Json
      ): IO[String] =
        capturedServer = serverName
        capturedTool = toolName
        capturedArgs = arguments
        IO.pure("""[{"number":42,"title":"Fix login bug"}]""")

    makeGateway(stubRegistry).complete(githubRequest).unsafeRunSync()

    capturedServer shouldBe "github"
    capturedTool shouldBe "search"
    capturedArgs shouldBe Json.obj(
      "query" -> Json.fromString("user:akreit"),
      "limit" -> Json.fromDoubleOrNull(5)
    )
  }

  it should "return the final text response after tool calls complete" in {
    val stubRegistry = new McpRegistry(Map.empty):
      override def toolSpecs(
          serverNames: Option[List[String]]
      ): IO[Option[List[ToolDefinition]]] =
        IO.pure(Some(List(searchTool)))
      override def execute(
          serverName: String,
          toolName: String,
          arguments: Json
      ): IO[String] =
        IO.pure("""[{"number":42,"title":"Fix login bug"}]""")

    val result =
      makeGateway(stubRegistry).complete(githubRequest).unsafeRunSync()

    result.map(_.response.id) shouldBe Right("msg-2")
    result.map(_.response.stopReason) shouldBe Right(Some("end_turn"))
    result.map(_.response.content) shouldBe Right(
      List(ContentBlock.Text("Search complete"))
    )
  }
