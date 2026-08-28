package com.github.akreit.config

import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
import pureconfig.ConfigReader
import sttp.model.Header

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

/** Configuration for a single MCP server. Either `command` (local stdio
  * subprocess) or `url` (remote HTTP server) must be set; `args`/`env` only
  * apply to the former, `headers` only to the latter.
  *
  * Fields other than `command`/`url` are `Option` rather than defaulted
  * collections: pureconfig's native Scala 3 `derives ConfigReader` ignores
  * constructor default values (only `ReadsMissingKeys` types like `Option`
  * default when a key is absent), so a bare `List`/`Map` default would still
  * fail with "Key not found" if omitted from the config.
  */
case class McpServerConfig(
    command: Option[String],
    args: Option[List[String]],
    url: Option[String],
    headerMap: Option[Map[String, String]],
    env: Option[Map[String, String]]
) derives ConfigReader {

  // convert raw map to a typed sequence of sttp Headers for use in HTTP client requests
  val headers: Option[Seq[Header]] =
    headerMap.map(_.map { case (k, v) => Header(k, v) }.toSeq)
}
