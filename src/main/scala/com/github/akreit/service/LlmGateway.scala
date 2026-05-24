package com.github.akreit.service

import cats.effect.IO
import com.github.akreit.model.ClientRequest
import com.github.akreit.model.LlmError
import com.github.akreit.model.ToolCallMade
import sttp.ai.claude.responses.MessageResponse

case class LlmResult(
    response: MessageResponse,
    toolCallsMade: List[ToolCallMade]
)

/** Gateway for sending messages to an LLM and receiving responses. */
trait LlmGateway:

  /** Sends the client request to the LLM and returns either an [[LlmError]] or
    * an [[LlmResult]] containing the final [[MessageResponse]] and the list of
    * tool calls made during the agent loop.
    */
  def complete(request: ClientRequest): IO[Either[LlmError, LlmResult]]
