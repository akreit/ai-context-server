package com.github.akreit

import sttp.tapir.*
import cats.effect.IO
import com.github.akreit.model.{AssistantMessage, ClientRequest, CompletionResponse, ToolCallMade, Usage}
import io.opentelemetry.api.OpenTelemetry
import sttp.tapir.generic.auto.*
import sttp.tapir.json.jsoniter.*
import sttp.tapir.server.metrics.opentelemetry.OpenTelemetryMetrics
import sttp.tapir.server.ServerEndpoint
import sttp.tapir.swagger.bundle.SwaggerInterpreter
import sttp.tapir.server.interceptor.metrics.MetricsRequestInterceptor

object Endpoints {

  val contextEndpoint: PublicEndpoint[ClientRequest, Unit, CompletionResponse, Any] = endpoint.post
    .in("v1" / "context" / "completions")
    .in(jsonBody[ClientRequest])
    .out(jsonBody[CompletionResponse])

  private[akreit] val contextServerEndpoint: ServerEndpoint[Any, IO] =
    contextEndpoint.serverLogicSuccess { request =>
      IO.pure(buildCompletionResponse(request))
    }

  // additional routes for swagger docs
  private val swaggerEndpoints: List[ServerEndpoint[Any, IO]] =
    SwaggerInterpreter()
      .fromServerEndpoints[IO](
        List(contextServerEndpoint),
        "Context-as-a-Service API",
        "1.0"
      )

  val all: List[ServerEndpoint[Any, IO]] = contextServerEndpoint :: swaggerEndpoints

  private def buildCompletionResponse(request: ClientRequest): CompletionResponse = {
    val normalizedMessage = request.message.trim
    val sourceLabels = request.additionalSources.map(_.toString)
    val sourceSummary =
      if sourceLabels.isEmpty then "no additional sources"
      else sourceLabels.mkString(", ")

    val content =
      s"Received '${normalizedMessage}' for user ${request.userId}. Enabled context sources: ${sourceSummary}."

    CompletionResponse(
      id = s"cmpl-${request.timestamp}",
      sessionId = request.userId,
      model = "stub-context-model",
      provider = "local",
      message = AssistantMessage(
        role = "assistant",
        content = content
      ),
      toolCallsMade = sourceLabels.map(source =>
        ToolCallMade(
          tool = "context_lookup",
          source = source,
          cacheHit = false
        )
      ),
      usage = Usage(
        promptTokens = normalizedMessage.length,
        completionTokens = content.length,
        totalTokens = normalizedMessage.length + content.length,
        toolRounds = sourceLabels.size
      ),
      finishReason = "stop"
    )
  }

  def metricsInterceptor(otel: OpenTelemetry): MetricsRequestInterceptor[IO] =
    OpenTelemetryMetrics.default[IO](otel.getMeter("ai-context-server")).metricsInterceptor()
}

