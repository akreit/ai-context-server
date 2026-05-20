package com.github.akreit.server

import cats.effect.IO
import com.github.akreit.model.AssistantMessage
import com.github.akreit.model.ClientRequest
import com.github.akreit.model.CompletionResponse
import com.github.akreit.model.ErrorResponse
import com.github.akreit.model.LlmError
import com.github.akreit.model.Usage
import com.github.akreit.service.LlmGateway
import io.opentelemetry.api.OpenTelemetry
import sttp.ai.claude.models.ContentBlock
import sttp.ai.claude.responses.MessageResponse
import sttp.model.StatusCode
import sttp.tapir.*
import sttp.tapir.generic.auto.*
import sttp.tapir.json.jsoniter.*
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.server.interceptor.metrics.MetricsRequestInterceptor
import sttp.tapir.server.metrics.opentelemetry.OpenTelemetryMetrics
import sttp.tapir.swagger.bundle.SwaggerInterpreter

class Endpoints(llmGateway: LlmGateway) {

  private val contextEndpoint: PublicEndpoint[
    ClientRequest,
    (StatusCode, ErrorResponse),
    CompletionResponse,
    Any
  ] =
    endpoint.post
      .in("v1" / "context" / "completions")
      .in(jsonBody[ClientRequest])
      .out(jsonBody[CompletionResponse])
      .errorOut(statusCode.and(jsonBody[ErrorResponse]))

  /** Server endpoint that handles incoming client requests, forwards them to
    * the LLM. Add `additionalSources` from the request and pass on to server
    * logic (which hands it down to LLM).
    */
  private[akreit] val contextServerEndpoint: ServerEndpoint[Any, IO] =
    contextEndpoint.serverLogic { request =>
      llmGateway
        .complete(request)
        .map(_.left.map(mapError).map(constructResponse(request, _)))
    }

  /** propagate llm errors to appropriate HTTP status codes and error messages
    * for the client
    *
    * @param error
    *   [[LlmError]] returned from the LLM gateway
    * @return
    *   a tuple of (HTTP status code, error response body) to return to the
    *   client
    */
  private def mapError(error: LlmError): (StatusCode, ErrorResponse) =
    error match
      case LlmError.Timeout(msg)        => ErrorResponse.timeout(msg)
      case LlmError.ApiError(code, msg) =>
        ErrorResponse.badGateway(s"LLM returned $code: $msg")
      case LlmError.ConnectionFailed(cause) =>
        ErrorResponse.serviceUnavailable(cause.getMessage)
      case LlmError.Unexpected(cause) =>
        ErrorResponse.internalError(cause.getMessage)

  private def constructResponse(
      request: ClientRequest,
      llmResponse: MessageResponse
  ): CompletionResponse =
    CompletionResponse(
      id = s"cmpl-${request.timestamp}",
      sessionId = request.userId,
      model = llmResponse.model,
      provider = "anthropic",
      message = AssistantMessage(
        role = "assistant",
        content = llmResponse.content.collect {
          case ContentBlock.TextContent(text, _) => text
        }.mkString
      ),
      // TODO: how can we get this information?
      toolCallsMade = Nil,
      usage = Usage(
        inputTokens = llmResponse.usage.inputTokens,
        outputTokens = llmResponse.usage.outputTokens,
        totalTokens =
          llmResponse.usage.inputTokens + llmResponse.usage.outputTokens,
        toolRounds = llmResponse.content.count {
          case ContentBlock.ToolResultContent(_, _, _) =>
            println(
              "Found a tool result content block, counting towards tool rounds"
            )
            true
          case ContentBlock.ToolUseContent(id, name, input) =>
            println(
              s"Found a tool use content block for tool '$name', counting towards tool rounds"
            )
            false
          case ContentBlock.TextContent(text, _) =>
            println(
              s"Found a text content block with text: $text, not counting towards tool rounds"
            )
            false
          case _ =>
            println(
              "Found a content block of an unrecognized type, not counting towards tool rounds"
            )
            false
        }
      ),
      finishReason = llmResponse.stopReason.getOrElse("end_turn")
    )

  private val swaggerEndpoints: List[ServerEndpoint[Any, IO]] =
    SwaggerInterpreter()
      .fromServerEndpoints[IO](
        List(contextServerEndpoint),
        "Context-as-a-Service API",
        "1.0"
      )

  val all: List[ServerEndpoint[Any, IO]] =
    contextServerEndpoint :: swaggerEndpoints
}

object Endpoints {
  def metricsInterceptor(otel: OpenTelemetry): MetricsRequestInterceptor[IO] =
    OpenTelemetryMetrics
      .default[IO](otel.getMeter("ai-context-server"))
      .metricsInterceptor()
}
