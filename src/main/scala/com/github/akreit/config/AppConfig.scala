package com.github.akreit.config

import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
import pureconfig.ConfigReader

/** Top-level application configuration. */
case class AppConfig(
    server: ServerConfig,
    claude: ClaudeConfig,
    mcpServers: Map[String, McpServerConfig]
) derives ConfigReader

case class ServerConfig(host: String, port: Int) derives ConfigReader:
  def resolvedHost: Host =
    Host
      .fromString(host)
      .getOrElse(throw IllegalArgumentException(s"Invalid host: $host"))
  def resolvedPort: Port =
    Port
      .fromInt(port)
      .getOrElse(throw IllegalArgumentException(s"Invalid port: $port"))

case class ClaudeConfig(
    apiKey: String,
    model: String,
    maxTokens: Int,
    systemPrompt: Option[String] = None
) derives ConfigReader

case class McpServerConfig(
    command: String,
    args: List[String],
    env: Map[String, String] = Map.empty
) derives ConfigReader
