package com.github.akreit.service

import java.net.http.HttpTimeoutException

import cats.effect.IO
import cats.syntax.all.*
import com.github.akreit.config.ClaudeConfig as AppClaudeConfig
import com.github.akreit.mcp.McpRegistry
import com.github.akreit.model.ClientRequest
import com.github.akreit.model.LlmError
import com.github.akreit.utils.CatsLogger
import sttp.ai.claude.ClaudeClient
import sttp.ai.claude.config.ClaudeConfig
import sttp.ai.claude.models.ContentBlock
import sttp.ai.claude.models.Message
import sttp.ai.claude.requests.MessageRequest
import sttp.ai.claude.responses.MessageResponse
import sttp.client4.Backend
import sttp.client4.httpclient.cats.HttpClientCatsBackend

/** LLM gateway backed by sttp-ai's Claude module. */
class ClaudeLlmGateway(
    client: ClaudeClient,
    model: String,
    maxTokens: Int,
    systemPrompt: Option[String],
    mcpRegistry: McpRegistry
) extends LlmGateway
    with CatsLogger:

  /** Sends a client request to the Claude LLM, running an agent loop that
    * executes tool calls until the model returns a final text response.
    */
  override def complete(
      clientRequest: ClientRequest
  ): IO[Either[LlmError, MessageResponse]] =
    val mcpServerNames = clientRequest.additionalSources.map(_.map(_.name))
    logger.debug(
      s"complete called: additionalSources=${clientRequest.additionalSources}, mcpServerNames=$mcpServerNames"
    ) >>
      mcpRegistry.toolSpecs(mcpServerNames).flatMap { mcpTools =>
        val claudeTools = mcpTools.map(_.map(ToolAdapter.fromJavaMcpTool))
        HttpClientCatsBackend.resource[IO]().use { backend =>
          agentLoop(
            messages = List(Message.user(clientRequest.message)),
            tools = claudeTools,
            serverNames = mcpServerNames.getOrElse(Nil),
            backend = backend
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
    */
  private def agentLoop(
      messages: List[Message],
      tools: Option[List[sttp.ai.claude.models.Tool]],
      serverNames: List[String],
      backend: Backend[IO]
  ): IO[Either[Exception, MessageResponse]] =
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
          logger.info(
            s"Claude response: stopReason=${response.stopReason}, usage=${response.usage}"
          )
      }
      .flatMap {
        case Left(error) => IO.pure(Left(error))
        case Right(response) if response.stopReason.contains("tool_use") =>
          executeToolCalls(response, serverNames).flatMap { toolResultMsg =>
            agentLoop(
              messages = messages :+ Message
                .assistant(response.content) :+ toolResultMsg,
              tools = tools,
              serverNames = serverNames,
              backend = backend
            )
          }
        case Right(response) => IO.pure(Right(response))
      }

  /** Collects all [[ContentBlock.ToolUseContent]] blocks from the response,
    * executes each via [[McpRegistry]], and returns a single user message
    * containing all [[ContentBlock.ToolResultContent]] blocks.
    */
  private def executeToolCalls(
      response: MessageResponse,
      serverNames: List[String]
  ): IO[Message] =
    val toolUses = response.content.collect {
      case t: ContentBlock.ToolUseContent => t
    }
    toolUses
      .traverse { toolUse =>
        val serverName = serverNames.headOption.getOrElse("")
        val args = toolUse.input.map { case (k, v) =>
          k -> v.toString.asInstanceOf[AnyRef]
        }
        logger.info(
          s"Executing tool '${toolUse.name}' on server '$serverName'"
        ) >>
          mcpRegistry
            .execute(serverName, toolUse.name, args)
            .map(result =>
              ContentBlock
                .ToolResultContent(toolUseId = toolUse.id, content = result)
            )
            .handleError(e =>
              ContentBlock.ToolResultContent(
                toolUseId = toolUse.id,
                content = e.getMessage,
                isError = Some(true)
              )
            )
      }
      .map(results => Message.user(results))

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
