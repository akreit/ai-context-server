package com.github.akreit.model

import com.github.plokhotnyuk.jsoniter_scala.core.JsonValueCodec
import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec

/** Represents a request from the client, containing the user's message and any
  * additional context sources they want to include in the response.
  * @param userId
  *   the ID of the user making the request
  * @param message
  *   the message or query from the user
  * @param additionalSources
  *   list of additional [[ContextSource]] that the user wants to include in the
  *   response
  * @param timestamp
  *   the time when the request was made, represented as a Unix timestamp in
  *   milliseconds
  */
case class ClientRequest(
    userId: String,
    message: String,
    additionalSources: Option[List[ContextSource]],
    timestamp: Long
) derives ConfiguredJsonValueCodec
