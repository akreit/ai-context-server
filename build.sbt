val tapirVersion = "1.13.19"
val otelVersion = "1.62.0"

lazy val rootProject = (project in file(".")).settings(
  Seq(
    name := "ai-context-server",
    version := "0.1.0-SNAPSHOT",
    organization := "com.github.akreit",
    scalaVersion := "3.8.3",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server" % tapirVersion,
      "org.http4s" %% "http4s-ember-server" % "0.23.34",
      "com.softwaremill.sttp.tapir" %% "tapir-opentelemetry-metrics" % tapirVersion,
      "io.opentelemetry" % "opentelemetry-exporter-otlp" % otelVersion,
      "io.opentelemetry" % "opentelemetry-sdk-extension-autoconfigure" % otelVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % tapirVersion,
      "com.softwaremill.sttp.tapir"           %% "tapir-jsoniter-scala"    % tapirVersion,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros"   % "2.38.12",
      "ch.qos.logback" % "logback-classic" % "1.5.32",
      "com.softwaremill.sttp.tapir" %% "tapir-sttp-stub4-server" % tapirVersion % Test,
      "org.scalatest" %% "scalatest" % "3.2.20" % Test
    )
  )
)
