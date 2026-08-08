package com.github.akreit.claude

import java.net.http.HttpTimeoutException

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import com.github.akreit.cache.CacheKey
import com.github.akreit.cache.ToolResultCache
import com.github.akreit.config.ClaudeConfig as AppClaudeConfig
import com.github.akreit.mcp.McpRegistry
import com.github.akreit.model.ClientRequest
import com.github.akreit.model.LlmError
import com.github.akreit.model.ToolCallMade
import com.github.akreit.service.LlmGateway
import com.github.akreit.service.LlmResult
import com.github.akreit.utils.CatsLogger
import io.circe.Json
import sttp.ai.claude.ClaudeClient
import sttp.ai.claude.config.ClaudeConfig
import sttp.ai.claude.models.ContentBlock
import sttp.ai.claude.models.Message
import sttp.ai.claude.requests.MessageRequest
import sttp.ai.claude.responses.MessageResponse
import sttp.client4.Backend
import sttp.client4.httpclient.cats.HttpClientCatsBackend

/** LLM gateway backed by sttp-ai's Claude module.
  *
  * Implements an agent loop that allows Claude to call tools via the MCP
  * registry. Sequence is: * *
  */
class ClaudeLlmGateway(
    client: ClaudeClient,
    model: String,
    maxTokens: Int,
    systemPrompt: Option[String],
    mcpRegistry: McpRegistry,
    backendResource: Resource[IO, Backend[IO]] =
      HttpClientCatsBackend.resource[IO]()
) extends LlmGateway
    with CatsLogger:

  /** Sends a client request to the Claude LLM, running an agent loop that
    * executes tool calls until the model returns a final text response.
    */
  override def complete(
      clientRequest: ClientRequest
  ): IO[Either[LlmError, LlmResult]] =
    val mcpServerNames = clientRequest.additionalSources.map(_.map(_.name))
    logger.debug(
      s"complete called: additionalSources=${clientRequest.additionalSources}, mcpServerNames=$mcpServerNames"
    ) >>
      mcpRegistry.toolSpecs(mcpServerNames).flatMap { mcpTools =>
        // map between MCP tool definitions of sttp-ai and chimp
        val claudeTools = mcpTools.map(_.map(ToolAdapter.fromChimpTool))

        // execute agent loop recursively
        backendResource.use { backend =>
          ToolResultCache.inMemory.flatMap { cache =>
            agentLoop(
              messages = List(Message.user(clientRequest.message)),
              tools = claudeTools,
              serverNames = mcpServerNames.getOrElse(Nil),
              backend = backend,
              accToolCalls = Nil,
              cache = cache
            ).map(
              _.left.map(error =>
                LlmError.ApiError(statusCode = 0, message = error.getMessage)
              )
            ).handleError {
              case e: HttpTimeoutException =>
                Left(LlmError.Timeout(e.getMessage))
              case e: java.net.ConnectException =>
                Left(LlmError.ConnectionFailed(e))
              case e => Left(LlmError.Unexpected(e))
            }
          }
        }
      }

  /** Recursive agent loop that calls Claude and, on `tool_use`, executes tools
    * via [[McpRegistry]] (consulting `cache` first) and recurses with results
    * appended. Terminates when Claude returns a non-tool stop reason.
    *
    * TODO: investigate if agent loop should only continue when tools are used?
    */
  private def agentLoop(
      messages: List[Message],
      tools: Option[List[sttp.ai.claude.models.Tool]],
      serverNames: List[String],
      backend: Backend[IO],
      accToolCalls: List[ToolCallMade],
      cache: ToolResultCache
  ): IO[Either[Exception, LlmResult]] =
    val request = MessageRequest(
      model = model,
      system = systemPrompt,
      messages = messages,
      maxTokens = maxTokens,
      tools = tools
    )
    client
      .createMessage(request)
      .send(backend)
      .map(_.body)
      .flatTap {
        case Left(error) =>
          logger.error(s"Error response from Claude API: ${error.getMessage}")
        case Right(response) =>
          logger.debug(
            s"""Claude response:
               | response=${response.content.mkString(",")},
               | stopReason=${response.stopReason},
               | usage=${response.usage}
            """.stripMargin
          )
      }
      .flatMap {
        case Left(error) => IO.pure(Left(error))
        case Right(response) if response.stopReason.contains("tool_use") =>
          executeToolCalls(response, serverNames, cache).flatMap {
            (toolResultMsg, newToolCalls) =>
              agentLoop(
                messages = messages :+ Message
                  .assistant(response.content) :+ toolResultMsg,
                tools = tools,
                serverNames = serverNames,
                backend = backend,
                accToolCalls = accToolCalls ++ newToolCalls,
                cache = cache
              )
          }
        case Right(response) =>
          IO.pure(Right(LlmResult(response, accToolCalls)))
      }

  /** Collects all [[ContentBlock.ToolUse]] blocks from the response, executes
    * each via [[McpRegistry]], and returns a single user message containing all
    * [[ContentBlock.ToolResult]] blocks.
    *
    * Uses a cache to avoid re-executing tool calls with the same name and
    * input, keyed by [[CacheKey]].
    */
  private def executeToolCalls(
      response: MessageResponse,
      serverNames: List[String],
      cache: ToolResultCache
  ): IO[(Message, List[ToolCallMade])] =
    val toolUses = response.content.collect { case t: ContentBlock.ToolUse =>
      t
    }
    toolUses
      .traverse { toolUse =>
        val serverName = serverNames.headOption.getOrElse("")
        val args = toolUse.input
        val cacheKey = CacheKey.fromArgs(
          toolUse.name,
          args.view
            .mapValues(v =>
              io.circe.Printer.noSpaces.copy(sortKeys = true).print(v)
            )
            .toMap
        )
        cache.get(cacheKey).flatMap {
          case Some(cached) =>
            logger
              .info(
                s"Cache hit for tool '${toolUse.name}' on server '$serverName'"
              )
              .as(
                (
                  ContentBlock.ToolResult(
                    toolUseId = toolUse.id,
                    content = cached
                  ),
                  ToolCallMade(
                    tool = toolUse.name,
                    source = serverName,
                    cacheHit = true
                  )
                )
              )
          case None =>
            logger.info(
              s"Executing tool '${toolUse.name}' on server '$serverName' with input: ${toolUse.input}"
            ) >>
              mcpRegistry
                .execute(serverName, toolUse.name, Json.fromFields(args))
                .flatTap(result => cache.put(cacheKey, result))
                .map(result =>
                  (
                    ContentBlock
                      .ToolResult(
                        toolUseId = toolUse.id,
                        content = result
                      ),
                    ToolCallMade(
                      tool = toolUse.name,
                      source = serverName,
                      cacheHit = false
                    )
                  )
                )
                .handleError(e =>
                  (
                    ContentBlock.ToolResult(
                      toolUseId = toolUse.id,
                      content = e.getMessage,
                      isError = Some(true)
                    ),
                    ToolCallMade(
                      tool = toolUse.name,
                      source = serverName,
                      cacheHit = false
                    )
                  )
                )
        }
      }
      .map { pairs =>
        val (resultBlocks, toolCalls) = pairs.unzip
        (Message.user(resultBlocks), toolCalls)
      }

object ClaudeLlmGateway:

  def fromConfig(
      config: AppClaudeConfig,
      mcpRegistry: McpRegistry
  ): ClaudeLlmGateway =
    val sttpConfig = ClaudeConfig(apiKey = config.apiKey)
    val client = ClaudeClient(sttpConfig)
    ClaudeLlmGateway(
      client,
      config.model,
      config.maxTokens,
      config.systemPrompt,
      mcpRegistry
    )
