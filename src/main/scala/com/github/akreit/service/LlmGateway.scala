package com.github.akreit.service

import cats.effect.IO
import com.github.akreit.model.LlmError
import sttp.ai.claude.responses.MessageResponse

/** Gateway for sending messages to an LLM and receiving responses. */
trait LlmGateway:

  /** Sends a user message to the LLM and returns either an [[LlmError]] or an
    * [[LlmResponse]].
    */
  def complete(userMessage: String): IO[Either[LlmError, MessageResponse]]
