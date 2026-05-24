package com.github.akreit

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import com.github.akreit.config.AppConfig
import com.github.akreit.mcp.McpRegistry
import com.github.akreit.server.Endpoints
import com.github.akreit.service.ClaudeLlmGateway
import com.github.akreit.utils.CatsLogger
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import pureconfig.ConfigSource
import pureconfig.module.catseffect.syntax.*
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.server.http4s.Http4sServerOptions

object Main extends IOApp with CatsLogger:

  /**
   * Initializes and starts the HTTP server with the given configuration and MCP registry.
   * Starts a [[LlmGateway]] and HTTP routes, and serves the API until the process is stopped.
   * @param config application configuration loaded from file/env
   * @param mcpRegistry registry of MCP servers and tools, used to construct the LLM gateway with access to tools
   *                    
   * @return an effect that runs the server and never completes until the process is stopped, yielding an exit code of success
   */
  private[akreit] def initContextServer(config: AppConfig, mcpRegistry: McpRegistry): IO[ExitCode] = {
    for
      llmGateway = ClaudeLlmGateway.fromConfig(config.claude, mcpRegistry)
      endpoints = Endpoints(llmGateway)

      otel = AutoConfiguredOpenTelemetrySdk
        .initialize()
        .getOpenTelemetrySdk

      serverOptions = Http4sServerOptions
        .customiseInterceptors[IO]
        .metricsInterceptor(Endpoints.metricsInterceptor(otel))
        .options

      routes = Http4sServerInterpreter[IO](serverOptions).toRoutes(
        endpoints.all
      )

      exitCode <- EmberServerBuilder
        .default[IO]
        .withHost(config.server.resolvedHost)
        .withPort(config.server.resolvedPort)
        .withHttpApp(Router[IO]("/" -> routes).orNotFound)
        .build
        .use: server =>
          IO.println(
            s"Go to ${server.address}/docs to open SwaggerUI. Stop the process to exit."
          ) *>
            IO.never
        .as(ExitCode.Success)
    yield exitCode
  }

  /**
   * main entrypoint for this application
   */
  override def run(args: List[String]): IO[ExitCode] =
    for
      config <- ConfigSource.default.loadF[IO, AppConfig]()
      exitCode <- McpRegistry.build(config.mcpServers).use { mcpRegistry =>
        initContextServer(config, mcpRegistry)
      }
    yield exitCode
