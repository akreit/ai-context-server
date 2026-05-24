package com.github.akreit.model

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.JsonCodecMaker

case class AssistantMessage(
    role: String,
    content: String
)

object AssistantMessage {
  given JsonValueCodec[AssistantMessage] = JsonCodecMaker.make
}

case class ToolCallMade(
    tool: String,
    source: String,
    cacheHit: Boolean
)

object ToolCallMade {
  given JsonValueCodec[ToolCallMade] = JsonCodecMaker.make
}


case class Usage(
    inputTokens: Int,
    outputTokens: Int,
    totalTokens: Int,
    toolRounds: Int
)

object Usage {
  given JsonValueCodec[Usage] = JsonCodecMaker.make
}

/** Represents the response sent back to the client after processing a
  * completion request. Very similar to sttp-ai's MessageResponse, but we
  * implement our own model to couple from external library.
  * @param id
  *   request response, kept along with sessionId for tracing and debugging
  *   purposes
  * @param sessionId
  *   the user/session ID associated with this response, for tracing and
  *   debugging purposes
  * @param model
  *   the LLM model used to generate the response, for informational purposes
  * @param provider
  *   the LLM provider (e.g. "anthropic") used to generate the response, for
  *   informational purposes
  * @param message
  *   the assistant's reply message content and role
  * @param toolCallsMade
  *   a list of tools that were called during the generation of this response,
  *   along with metadata about each call (e.g. whether it was a cache hit)
  * @param usage
  *   token usage information for this response, including input tokens, output
  *   tokens, total tokens, and tool rounds
  * @param finishReason
  *   the reason why the LLM stopped generating content (e.g. "end_turn",
  *   "max_tokens", etc.)
  */
case class CompletionResponse(
    id: String,
    sessionId: String,
    model: String,
    provider: String,
    message: AssistantMessage,
    toolCallsMade: List[ToolCallMade],
    usage: Usage,
    finishReason: String
) derives ConfiguredJsonValueCodec
