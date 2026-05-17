package com.github.akreit.service

import java.net.http.HttpTimeoutException

import cats.effect.IO
import com.github.akreit.config.ClaudeConfig as AppClaudeConfig
import com.github.akreit.model.LlmError
import com.github.akreit.utils.CatsLogger
import sttp.ai.claude.ClaudeClient
import sttp.ai.claude.config.ClaudeConfig
import sttp.ai.claude.models.Message
import sttp.ai.claude.requests.MessageRequest
import sttp.ai.claude.responses.MessageResponse
import sttp.client4.httpclient.cats.HttpClientCatsBackend

/** LLM gateway backed by sttp-ai's Claude module. */
class ClaudeLlmGateway(
    client: ClaudeClient,
    model: String,
    maxTokens: Int
) extends LlmGateway
    with CatsLogger:

  /** Sends a user message to the Claude LLM and returns the assistant's reply,
    * along with token usage and other metadata.
    *
    * @param userMessage
    *   the message from the user to send to the LLM
    * @return
    *   [[MessageResponse]] wrapped in an IO monad, including content and
    *   metadata
    */
  override def complete(
      userMessage: String
  ): IO[Either[LlmError, MessageResponse]] =
    HttpClientCatsBackend.resource[IO]().use { backend =>
      // TODO: include tools and system instructions in the request as needed
      val request = MessageRequest.simple(
        model = model,
        messages = List(Message.user(userMessage)),
        maxTokens = maxTokens
      )

      backend
        .send(client.createMessage(request))
        .map(_.body)
        .flatTap {
          case Left(error) =>
            logger.error(s"Error response from Claude API: ${error.getMessage}")
          case Right(response) =>
            logger.info(
              s"Received response from Claude API with usage: ${response.usage}"
            )
        }
        // only handle Left, Right is passed through
        .map(
          _.left.map(error =>
            LlmError.ApiError(statusCode = 0, message = error.getMessage)
          )
        )
        .handleError {
          case e: HttpTimeoutException => Left(LlmError.Timeout(e.getMessage))
          case e: java.net.ConnectException =>
            Left(LlmError.ConnectionFailed(e))
          case e => Left(LlmError.Unexpected(e))
        }
    }

object ClaudeLlmGateway:

  def fromConfig(config: AppClaudeConfig): ClaudeLlmGateway =
    val sttpConfig = ClaudeConfig(apiKey = config.apiKey)
    val client = ClaudeClient(sttpConfig)
    ClaudeLlmGateway(client, config.model, config.maxTokens)
