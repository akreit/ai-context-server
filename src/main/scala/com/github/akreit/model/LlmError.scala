package com.github.akreit.model

/** Errors that can occur when communicating with the LLM. */
enum LlmError:
  /** The LLM service did not respond in time. */
  case Timeout(message: String)

  /** The LLM service returned an HTTP error (4xx/5xx). */
  case ApiError(statusCode: Int, message: String)

  /** A network or connection-level failure. */
  case ConnectionFailed(cause: Throwable)

  /** Any other unexpected failure. */
  case Unexpected(cause: Throwable)
