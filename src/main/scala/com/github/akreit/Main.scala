package com.github.akreit

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
import com.github.akreit.config.AppConfig
import com.github.akreit.server.Endpoints
import com.github.akreit.service.ClaudeLlmGateway
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import pureconfig.ConfigSource
import pureconfig.module.catseffect.syntax.*
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.server.http4s.Http4sServerOptions

/** main entry point for the application. Sets up the LLM gateway, HTTP server,
  * and OpenTelemetry instrumentation.
  */
object Main extends IOApp:

  /** main method, wiring all components together and starting the server.
    *
    * @param args
    *   command-line arguments
    * @return
    */
  override def run(args: List[String]): IO[ExitCode] =
    for
      config <- ConfigSource.default.loadF[IO, AppConfig]()

      llmGateway = ClaudeLlmGateway.fromConfig(config.claude)
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

      host = Host
        .fromString(config.server.host)
        .getOrElse(
          throw IllegalArgumentException(s"Invalid host: ${config.server.host}")
        )
      port = Port
        .fromInt(config.server.port)
        .getOrElse(
          throw IllegalArgumentException(s"Invalid port: ${config.server.port}")
        )

      exitCode <- EmberServerBuilder
        .default[IO]
        .withHost(host)
        .withPort(port)
        .withHttpApp(Router[IO]("/" -> routes).orNotFound)
        .build
        .use: server =>
          IO.println(
            s"Go to ${server.address}/docs to open SwaggerUI. Stop the process to exit."
          ) *>
            IO.never
        .as(ExitCode.Success)
    yield exitCode
