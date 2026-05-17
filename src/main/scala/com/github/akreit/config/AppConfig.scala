package com.github.akreit.config

import pureconfig.ConfigReader

/** Top-level application configuration. */
case class AppConfig(
    server: ServerConfig,
    claude: ClaudeConfig
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
