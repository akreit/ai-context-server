package com.github.akreit.model

import com.github.plokhotnyuk.jsoniter_scala.macros.ConfiguredJsonValueCodec
import sttp.model.StatusCode

/** Represents an HTTP error response that can be sent back to the client.
  * Includes the HTTP status code, a short error description, and a detailed
  * error message.
  */
case class ErrorResponse(
    statusCode: Int,
    error: String,
    message: String
) derives ConfiguredJsonValueCodec

object ErrorResponse:

  def timeout(msg: String): (StatusCode, ErrorResponse) =
    (StatusCode.GatewayTimeout, ErrorResponse(504, "Gateway Timeout", msg))

  def badGateway(msg: String): (StatusCode, ErrorResponse) =
    (StatusCode.BadGateway, ErrorResponse(502, "Bad Gateway", msg))

  def serviceUnavailable(msg: String): (StatusCode, ErrorResponse) =
    (
      StatusCode.ServiceUnavailable,
      ErrorResponse(503, "Service Unavailable", msg)
    )

  def internalError(msg: String): (StatusCode, ErrorResponse) =
    (
      StatusCode.InternalServerError,
      ErrorResponse(500, "Internal Server Error", msg)
    )
