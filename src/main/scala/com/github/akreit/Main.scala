package com.github.akreit

import cats.effect.ExitCode
import cats.effect.IO
import cats.effect.IOApp
import com.comcast.ip4s.Host
import com.comcast.ip4s.Port
import com.comcast.ip4s.port
import com.github.akreit.server.Endpoints
import io.opentelemetry.sdk.autoconfigure.AutoConfiguredOpenTelemetrySdk
import org.http4s.ember.server.EmberServerBuilder
import org.http4s.server.Router
import sttp.tapir.server.http4s.Http4sServerInterpreter
import sttp.tapir.server.http4s.Http4sServerOptions

object Main extends IOApp:

  override def run(args: List[String]): IO[ExitCode] =
    val otel = AutoConfiguredOpenTelemetrySdk.initialize().getOpenTelemetrySdk
    val serverOptions: Http4sServerOptions[IO] =
      Http4sServerOptions
        .customiseInterceptors[IO]
        .metricsInterceptor(Endpoints.metricsInterceptor(otel))
        .options
    val routes =
      Http4sServerInterpreter[IO](serverOptions).toRoutes(Endpoints.all)
    val port = sys.env
      .get("HTTP_PORT")
      .flatMap(_.toIntOption)
      .flatMap(Port.fromInt)
      .getOrElse(port"8080")

    EmberServerBuilder
      .default[IO]
      .withHost(Host.fromString("localhost").get)
      .withPort(port)
      .withHttpApp(Router[IO]("/" -> routes).orNotFound)
      .build
      .use: server =>
        IO.println(
          s"Go to http://localhost:${server.address.getPort}/docs to open SwaggerUI. Stop the process to exit."
        ) *>
          IO.never
      .as(ExitCode.Success)
