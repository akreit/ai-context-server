package com.github.akreit.mcp

import scala.jdk.CollectionConverters.*

import cats.data.OptionT
import cats.effect.IO
import cats.effect.Resource
import cats.syntax.all.*
import com.github.akreit.config.McpServerConfig
import com.github.akreit.utils.CatsLogger
import io.modelcontextprotocol.client.McpClient
import io.modelcontextprotocol.client.McpSyncClient
import io.modelcontextprotocol.client.transport.ServerParameters
import io.modelcontextprotocol.client.transport.StdioClientTransport
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapperSupplier
import io.modelcontextprotocol.spec.McpSchema

/** Registry for MCP clients, which are initialized based on the application
  * config and provide methods to list available tools and execute them. Uses
  * the Java SDK's [[McpSyncClient]] model, but yields asynchronous APIs with
  * [[cats.effect.IO]] for better integration with the rest of the application
  * and to avoid blocking operations on the main execution context.
  *
  * @param clients
  *   represented as a map of logical server names to their corresponding MCP
  *   clients, allowing for multiple MCP servers to be configured and used in
  *   the application.
  */
class McpRegistry(private val clients: Map[String, McpSyncClient])
    extends CatsLogger:

  /** Retrieve the list of available tools from all configured MCP clients.
    *
    * @param serverNames
    *   optional list of logical server names to query for tools. When [[None]],
    *   no tools are fetched and [[None]] is returned. When [[Some]], tools are
    *   fetched from the specified servers; unknown names are skipped with a
    *   warning.
    * @return
    *   [[None]] if no server names were requested, or [[Some]] list of tools
    *   aggregated from the specified MCP servers.
    */
  def toolSpecs(
      serverNames: Option[List[String]]
  ): IO[Option[List[McpSchema.Tool]]] =
    logger.debug(s"toolSpecs called with serverNames=$serverNames") >>
      (for
        names <- OptionT.fromOption[IO](serverNames)
        tools <- OptionT.liftF(names.flatTraverse { name =>
          clients.get(name) match
            case Some(client) =>
              logger.info(s"Fetching tool specs from MCP client: $name") >>
                IO.blocking(client.listTools().tools.asScala.toList)
            case None =>
              logger.warn(s"No MCP client configured for: $name").as(Nil)
        })
      yield tools).value

  /** Execute a tool on a specified MCP server with the given arguments. The
    * result is returned as an effectful string, which may contain the tool's
    * output or any error messages. If the specified server name does not have a
    * corresponding client, an error is raised.
    * @param serverName
    *   logical name of the MCP server to execute the tool on, as defined in the
    *   application config
    * @param toolName
    *   name of the tool to execute, which should be one of the tools listed by
    *   the `toolSpecs` method for the specified server
    * @param args
    *   arguments to pass to the tool, represented as a map of string keys to
    *   arbitrary values. The structure and content of this map will depend on
    *   the specific tool being executed and its expected input parameters.
    * @return
    *   an effectful string containing the result of the tool execution, which
    *   may include the tool's output or any error messages.
    */
  def execute(
      serverName: String,
      toolName: String,
      args: Map[String, AnyRef]
  ): IO[String] =
    clients.get(serverName) match
      case Some(client) =>
        IO.blocking {
          val request = new McpSchema.CallToolRequest(toolName, args.asJava)
          val result = client.callTool(request)
          result.content.asScala
            .collect { case t: McpSchema.TextContent =>
              t.text
            }
            .mkString("\n")
        }
      case None =>
        IO.raiseError(
          RuntimeException(s"No MCP client configured for: $serverName")
        )

/** companion object with builder method to create a registry from the
  * application config, which may contain 1...n MCP server definitions
  */
object McpRegistry extends CatsLogger:

  def build(configs: Map[String, McpServerConfig]): Resource[IO, McpRegistry] =
    configs.toList
      .traverse { (name, cfg) => clientResource(name, cfg).map(name -> _) }
      .map(pairs => McpRegistry(pairs.toMap))
      .evalTap(registry =>
        logger.info(
          s"MCP registry created with ${registry.clients.size} client(s): ${registry.clients.keys.mkString(", ")}"
        )
      )

  /** start the local MCP server process and set up stdio client transport
    * (configurable)
    * @param name
    *   logical name of the MCP server, used to reference it in the API and logs
    * @param cfg
    *   [[McpServerConfig]] defined in the application config, containing the
    *   command to start the MCP server and its arguments
    * @return
    */
  private def clientResource(
      name: String,
      cfg: McpServerConfig
  ): Resource[IO, McpSyncClient] =
    Resource.make(
      logger.info(
        s"Initializing MCP client '$name' with command: ${cfg.command}"
      ) >>
        IO.blocking {
          val params =
            ServerParameters
              .builder(cfg.command)
              .args(cfg.args.asJava)
              .env(cfg.env.asJava)
              .build()
          val transport = new StdioClientTransport(
            params,
            new JacksonMcpJsonMapperSupplier().get()
          )
          val client = McpClient.sync(transport).build()
          client.initialize()
          client
        }
    )(client =>
      IO.blocking(client.close())
        .handleErrorWith(e =>
          logger.warn(s"Failed to close MCP client '$name': ${e.getMessage}")
        )
    )
