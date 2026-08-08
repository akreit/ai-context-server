package com.github.akreit.claude

import chimp.protocol.ToolDefinition
import io.circe.Json
import sttp.ai.claude.models.PropertySchema
import sttp.ai.claude.models.Tool
import sttp.ai.claude.models.ToolInputSchema

object ToolAdapter:

  /** Converts a chimp MCP tool specification to a sttp-ai Claude
    * [[Tool.Custom]].
    *
    * [[chimp.protocol.ToolDefinition.inputSchema]] is a JSON Schema encoded as
    * circe [[Json]], so we extract its `type`/`properties`/`required` fields
    * via the cursor API and map each property to a typed [[PropertySchema]].
    */
  def fromChimpTool(tool: ToolDefinition): Tool =
    val cursor = tool.inputSchema.hcursor
    Tool(
      name = tool.name,
      description = tool.description.getOrElse(""),
      inputSchema = ToolInputSchema(
        `type` = cursor.get[String]("type").getOrElse("object"),
        properties = cursor
          .downField("properties")
          .as[Map[String, Json]]
          .getOrElse(Map.empty)
          .view
          .mapValues(extractPropertySchema)
          .toMap,
        required = cursor.get[List[String]]("required").toOption
      )
    )

  private def extractPropertySchema(raw: Json): PropertySchema =
    val cursor = raw.hcursor
    PropertySchema(
      `type` = cursor.get[String]("type").getOrElse("string"),
      description = cursor.get[String]("description").toOption,
      `enum` = cursor.get[List[String]]("enum").toOption
    )
