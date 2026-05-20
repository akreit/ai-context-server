package com.github.akreit.service

import cats.effect.IO
import com.github.akreit.model.ClientRequest
import com.github.akreit.model.LlmError
import sttp.ai.claude.responses.MessageResponse

/** Gateway for sending messages to an LLM and receiving responses. */
trait LlmGateway:

  /** Sends the client request to the LLM and returns either an [[LlmError]] or
    * a [[MessageResponse]].
    */
  def complete(request: ClientRequest): IO[Either[LlmError, MessageResponse]]
