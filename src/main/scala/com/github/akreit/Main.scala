package com.github.akreit

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
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

  override def run(args: List[String]): IO[ExitCode] =
    for
      config   <- ConfigSource.default.loadF[IO, AppConfig]()
      exitCode <- McpRegistry.build(config.mcpServers).use { mcpRegistry =>
        for
          tools <- mcpRegistry.toolSpecs(config.mcpServers.keys.toList)
          _     <- logger.info(s"MCP tools loaded (${tools.size}): ${tools.map(_.name).mkString(", ")}")

          llmGateway = ClaudeLlmGateway.fromConfig(config.claude)
          endpoints  = Endpoints(llmGateway)

          otel = AutoConfiguredOpenTelemetrySdk
            .initialize()
            .getOpenTelemetrySdk

          serverOptions = Http4sServerOptions
            .customiseInterceptors[IO]
            .metricsInterceptor(Endpoints.metricsInterceptor(otel))
            .options

          routes = Http4sServerInterpreter[IO](serverOptions).toRoutes(endpoints.all)

          host = Host
            .fromString(config.server.host)
            .getOrElse(throw IllegalArgumentException(s"Invalid host: ${config.server.host}"))
          port = Port
            .fromInt(config.server.port)
            .getOrElse(throw IllegalArgumentException(s"Invalid port: ${config.server.port}"))

          exitCode <- EmberServerBuilder
            .default[IO]
            .withHost(host)
            .withPort(port)
            .withHttpApp(Router[IO]("/" -> routes).orNotFound)
            .build
            .use: server =>
              IO.println(s"Go to ${server.address}/docs to open SwaggerUI. Stop the process to exit.") *>
                IO.never
            .as(ExitCode.Success)
        yield exitCode
      }
    yield exitCode
