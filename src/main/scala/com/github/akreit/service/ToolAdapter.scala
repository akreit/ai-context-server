package com.github.akreit.service

import scala.jdk.CollectionConverters.*

import io.modelcontextprotocol.spec.McpSchema.Tool as McpTool
import sttp.ai.claude.models.PropertySchema
import sttp.ai.claude.models.Tool
import sttp.ai.claude.models.ToolInputSchema

object ToolAdapter:

  /** Converts an MCP tool specification to a sttp-ai Claude [[Tool.Custom]].
    *
    * [[io.modelcontextprotocol.spec.McpSchema.JsonSchema]] properties are raw
    * Jackson-deserialized `Map[String, Object]` values, so we extract each
    * property's fields by key and map them to a typed [[PropertySchema]].
    */
  def fromJavaMcpTool(mcpTool: McpTool): Tool =
    val schema = mcpTool.inputSchema()
    Tool(
      name = mcpTool.name(),
      description = mcpTool.description(),
      inputSchema = ToolInputSchema(
        `type` = Option(schema.`type`()).getOrElse("object"),
        properties = Option(schema.properties())
          .map(_.asScala.toMap.collect { case (propName, rawProp) =>
            propName -> extractPropertySchema(rawProp)
          })
          .getOrElse(Map.empty),
        required = Option(schema.required()).map(_.asScala.toList)
      )
    )

  private def extractPropertySchema(raw: Object): PropertySchema =
    raw match
      case m: java.util.Map[?, ?] =>
        val map = m.asInstanceOf[java.util.Map[String, Object]].asScala
        PropertySchema(
          `type` = map.get("type").map(_.toString).getOrElse("string"),
          description = map.get("description").map(_.toString),
          `enum` = map.get("enum").collect { case list: java.util.List[?] =>
            list.asScala.map(_.toString).toList
          }
        )
      case _ =>
        PropertySchema(`type` = "string")
