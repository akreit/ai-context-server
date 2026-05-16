package com.github.akreit.model

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
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
  promptTokens: Int,
  completionTokens: Int,
  totalTokens: Int,
  toolRounds: Int
)

object Usage {
  given JsonValueCodec[Usage] = JsonCodecMaker.make
}

case class CompletionResponse(
  id: String,
  sessionId: String,
  model: String,
  provider: String,
  message: AssistantMessage,
  toolCallsMade: List[ToolCallMade],
  usage: Usage,
  finishReason: String
)

object CompletionResponse {
  given JsonValueCodec[CompletionResponse] = JsonCodecMaker.make
}

