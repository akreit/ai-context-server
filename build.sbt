Global / onChangedBuildSource := ReloadOnSourceChanges

inThisBuild(
  List(
    version := "0.1.0-SNAPSHOT",
    scalaVersion := "3.8.3",
    semanticdbEnabled := true,
    semanticdbVersion := scalafixSemanticdb.revision,
    Compile / run / fork := true,
    scalacOptions := Seq(
      "-Werror",
      "Yfuture-lazy-vals",
      "-deprecation",
      "-Wunused:all"
    )
  )
)

val tapirVersion = "1.13.30"
val otelVersion = "1.64.0"
val sttpVersion = "4.0.26"

lazy val rootProject = (project in file(".")).settings(
  Seq(
    name := "ai-context-server",
    organization := "com.github.akreit",
    libraryDependencies ++= Seq(
      "com.softwaremill.sttp.tapir"           %% "tapir-http4s-server"                       % tapirVersion,
      "org.http4s"                            %% "http4s-ember-server"                       % "0.23.36",
      "com.softwaremill.sttp.tapir"           %% "tapir-opentelemetry-metrics"               % tapirVersion,
      "io.opentelemetry"                       % "opentelemetry-exporter-otlp"               % otelVersion,
      "io.opentelemetry"                       % "opentelemetry-sdk-extension-autoconfigure" % otelVersion,
      "com.softwaremill.sttp.tapir"           %% "tapir-swagger-ui-bundle"                   % tapirVersion,
      "com.softwaremill.sttp.tapir"           %% "tapir-jsoniter-scala"                      % tapirVersion,
      "com.github.plokhotnyuk.jsoniter-scala" %% "jsoniter-scala-macros"                     % "2.39.1",
      "ch.qos.logback"                         % "logback-classic"                           % "1.6.1",
      "com.github.pureconfig"                 %% "pureconfig-core"                           % "0.17.10",
      "com.github.pureconfig"                 %% "pureconfig-cats-effect"                    % "0.17.10",
      "com.softwaremill.sttp.ai"              %% "claude"                                    % "0.5.6",
      "com.softwaremill.sttp.ai"              %% "fs2"                                       % "0.5.6",
      "com.softwaremill.sttp.client4"         %% "cats"                                      % sttpVersion,
      "org.virtuslab"                          % "orca-claude_3"                             % "0.1.3",
      // TODO: Remove this dependency and replace with chimp
      "io.modelcontextprotocol.sdk"            % "mcp"                                       % "2.0.0",
      "com.softwaremill.sttp.tapir"           %% "tapir-sttp-stub4-server"                   % tapirVersion % Test,
      "org.scalatest"                         %% "scalatest"                                 % "3.2.20" % Test
    )
  )
)
