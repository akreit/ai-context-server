package com.github.akreit.service

import java.net.http.HttpTimeoutException

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import com.github.akreit.config.ClaudeConfig as AppClaudeConfig
import com.github.akreit.mcp.McpRegistry
import com.github.akreit.model.ClientRequest
import com.github.akreit.model.LlmError
import com.github.akreit.model.ToolCallMade
import com.github.akreit.utils.CatsLogger
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
        // map between MCP tool definitions of sttp-ai and mcp java sdk, see issue #3
        val claudeTools = mcpTools.map(_.map(ToolAdapter.fromJavaMcpTool))

        // execute agent loop recursively
        backendResource.use { backend =>
          agentLoop(
            messages = List(Message.user(clientRequest.message)),
            tools = claudeTools,
            serverNames = mcpServerNames.getOrElse(Nil),
            backend = backend,
            accToolCalls = Nil
          ).map(
            _.left.map(error =>
              LlmError.ApiError(statusCode = 0, message = error.getMessage)
            )
          ).handleError {
            case e: HttpTimeoutException => Left(LlmError.Timeout(e.getMessage))
            case e: java.net.ConnectException =>
              Left(LlmError.ConnectionFailed(e))
            case e => Left(LlmError.Unexpected(e))
          }
        }
      }

  /** Recursive agent loop. Calls Claude, and if it requests tool use, executes
    * the tools via [[McpRegistry]] and recurses with the results appended to
    * the conversation. Terminates when Claude stops requesting tools.
    *
    * TODO: investigate if agent loop should only continue when tools are used?
    */
  private def agentLoop(
      messages: List[Message],
      tools: Option[List[sttp.ai.claude.models.Tool]],
      serverNames: List[String],
      backend: Backend[IO],
      accToolCalls: List[ToolCallMade]
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
          executeToolCalls(response, serverNames).flatMap {
            (toolResultMsg, newToolCalls) =>
              agentLoop(
                messages = messages :+ Message
                  .assistant(response.content) :+ toolResultMsg,
                tools = tools,
                serverNames = serverNames,
                backend = backend,
                accToolCalls = accToolCalls ++ newToolCalls
              )
          }
        case Right(response) =>
          IO.pure(Right(LlmResult(response, accToolCalls)))
      }

  /** Collects all [[ContentBlock.ToolUseContent]] blocks from the response,
    * executes each via [[McpRegistry]], and returns a single user message
    * containing all [[ContentBlock.ToolResultContent]] blocks.
    */
  private def executeToolCalls(
      response: MessageResponse,
      serverNames: List[String]
  ): IO[(Message, List[ToolCallMade])] =
    val toolUses = response.content.collect {
      case t: ContentBlock.ToolUseContent => t
    }
    toolUses
      .traverse { toolUse =>
        val serverName = serverNames.headOption.getOrElse("")
        val args = toolUse.input.map { case (k, v) =>
          k -> ToolAdapter.ujsonToJavaArg(v)
        }
        logger.info(
          s"Executing tool '${toolUse.name}' on server '$serverName' with input: ${toolUse.input}"
        ) >>
          mcpRegistry
            .execute(serverName, toolUse.name, args)
            .map(result =>
              (
                ContentBlock
                  .ToolResultContent(toolUseId = toolUse.id, content = result),
                ToolCallMade(
                  tool = toolUse.name,
                  source = serverName,
                  cacheHit = false
                )
              )
            )
            .handleError(e =>
              (
                ContentBlock.ToolResultContent(
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
