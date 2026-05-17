package com.github.akreit.config

import pureconfig.ConfigReader

/** Top-level application configuration. */
case class AppConfig(
    server: ServerConfig,
    claude: ClaudeConfig,
    mcpServers: Map[String, McpServerConfig]
) derives ConfigReader

case class ServerConfig(
    host: String,
    port: Int
) derives ConfigReader

case class ClaudeConfig(
    apiKey: String,
    model: String,
    maxTokens: Int
) derives ConfigReader

case class McpServerConfig(
    command: String,
    args: List[String]
) derives ConfigReader
