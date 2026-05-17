package com.github.akreit.mcp

import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import com.github.akreit.config.McpServerConfig
import com.github.akreit.utils.CatsLogger
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.spec.McpSchema
import io.modelcontextprotocol.json.McpJsonMapper
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper

import scala.jdk.CollectionConverters.*

class McpRegistry(clients: Map[String, McpSyncClient]) extends CatsLogger:

  def toolSpecs(serverNames: List[String]): IO[List[McpSchema.Tool]] =
    serverNames.flatTraverse { name =>
      clients.get(name) match
        case Some(client) =>
          IO.blocking(client.listTools().tools.asScala.toList)
        case None =>
          logger.warn(s"No MCP client configured for: $name").as(Nil)
    }

  def execute(serverName: String, toolName: String, args: Map[String, AnyRef]): IO[String] =
    clients.get(serverName) match
      case Some(client) =>
        IO.blocking {
          val request = new McpSchema.CallToolRequest(toolName, args.asJava)
          val result  = client.callTool(request)
          result.content.asScala.collect {
            case t: McpSchema.TextContent => t.text
          }.mkString("\n")
        }
      case None =>
        IO.raiseError(RuntimeException(s"No MCP client configured for: $serverName"))

object McpRegistry extends CatsLogger:

  def build(configs: Map[String, McpServerConfig]): Resource[IO, McpRegistry] =
    configs.toList
      .traverse { (name, cfg) => clientResource(name, cfg).map(name -> _) }
      .map(pairs => McpRegistry(pairs.toMap))

  /**
   * start the local MCP server process and set up stdio client transport (configurable)
   * @param name logical name of the MCP server, used to reference it in the API and logs
   * @param cfg [[McpServerConfig]] defined in the application config, containing the command to start the MCP server and its arguments
   * @return
   */
  private def clientResource(name: String, cfg: McpServerConfig): Resource[IO, McpSyncClient] =
    Resource.make(
      IO.blocking {
        val params    = ServerParameters.builder(cfg.command).args(cfg.args.asJava).build()
        val transport = new StdioClientTransport(params, McpJsonMapper)
        val client    = McpClient.sync(transport).build()
        client.initialize()
        client
      }
    )(client => IO.blocking(client.close()).handleError(_ => ()))
