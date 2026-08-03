package com.github.akreit.service

import scala.jdk.CollectionConverters.*

import io.circe.Json
import io.modelcontextprotocol.spec.McpSchema.Tool as McpTool
import sttp.ai.claude.models.PropertySchema
import sttp.ai.claude.models.Tool
import sttp.ai.claude.models.ToolInputSchema

object ToolAdapter:

  /** Converts a circe JSON value to a Java object expected by the MCP registry
    * (java client). TODO: remove this once we switch to the chimp MCP client,
    * see issue #3
    */
  def ujsonToJavaArg(v: Json): AnyRef =
    v.fold(
      jsonNull = null,
      jsonBoolean = b => java.lang.Boolean.valueOf(b),
      jsonNumber = n => java.lang.Double.valueOf(n.toDouble),
      jsonString = s => s,
      jsonArray = a => a.view.map(ujsonToJavaArg).toSeq.asJava,
      jsonObject = o =>
        o.toMap.view.map { case (k, v) => k -> ujsonToJavaArg(v) }.toMap.asJava
    )

  /** Converts an MCP tool specification to a sttp-ai Claude [[Tool.Custom]].
    *
    * [[io.modelcontextprotocol.spec.McpSchema.Tool.inputSchema]] is a raw
    * Jackson-deserialized `Map[String, Object]` (JSON Schema), so we extract
    * its `type`/`properties`/`required` fields by key and map each property to
    * a typed [[PropertySchema]].
    *
    * TODO: switch to chimp once MCP client is available, see issue #3
    */
  def fromJavaMcpTool(mcpTool: McpTool): Tool =
    val schema = mcpTool.inputSchema().asScala
    Tool(
      name = mcpTool.name(),
      description = mcpTool.description(),
      inputSchema = ToolInputSchema(
        `type` = schema.get("type").map(_.toString).getOrElse("object"),
        properties = schema
          .get("properties")
          .collect { case m: java.util.Map[?, ?] =>
            m.asInstanceOf[java.util.Map[String, Object]]
              .asScala
              .toMap
              .collect { case (propName, rawProp) =>
                propName -> extractPropertySchema(rawProp)
              }
          }
          .getOrElse(Map.empty),
        required = schema.get("required").collect { case l: java.util.List[?] =>
          l.asScala.map(_.toString).toList
        }
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
