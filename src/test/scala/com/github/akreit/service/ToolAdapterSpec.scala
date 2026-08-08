package com.github.akreit.service

import chimp.protocol.ToolDefinition
import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.ai.claude.models.PropertySchema
import sttp.ai.claude.models.Tool
import sttp.ai.claude.models.ToolInputSchema

class ToolAdapterSpec extends AnyFlatSpec with Matchers:

  "fromChimpTool" should "convert name, description and required fields" in {
    val tool = ToolDefinition(
      name = "search",
      description = Some("Search GitHub for PRs and issues"),
      inputSchema = Json.obj(
        "type" -> Json.fromString("object"),
        "properties" -> Json.obj(
          "query" -> Json.obj(
            "type" -> Json.fromString("string"),
            "description" -> Json.fromString("GitHub search query")
          )
        ),
        "required" -> Json.arr(Json.fromString("query"))
      )
    )

    ToolAdapter.fromChimpTool(tool) shouldBe Tool(
      name = "search",
      description = "Search GitHub for PRs and issues",
      inputSchema = ToolInputSchema(
        `type` = "object",
        properties = Map(
          "query" -> PropertySchema(
            `type` = "string",
            description = Some("GitHub search query")
          )
        ),
        required = Some(List("query"))
      )
    )
  }

  it should "convert an enum property and default a missing description to empty" in {
    val tool = ToolDefinition(
      name = "setColor",
      description = None,
      inputSchema = Json.obj(
        "type" -> Json.fromString("object"),
        "properties" -> Json.obj(
          "color" -> Json.obj(
            "type" -> Json.fromString("string"),
            "enum" -> Json.arr(
              Json.fromString("red"),
              Json.fromString("green")
            )
          )
        )
      )
    )

    ToolAdapter.fromChimpTool(tool) shouldBe Tool(
      name = "setColor",
      description = "",
      inputSchema = ToolInputSchema(
        `type` = "object",
        properties = Map(
          "color" -> PropertySchema(
            `type` = "string",
            `enum` = Some(List("red", "green"))
          )
        ),
        required = None
      )
    )
  }

  it should "default missing type, properties and required to empty" in {
    val tool = ToolDefinition(
      name = "noop",
      description = None,
      inputSchema = Json.obj()
    )

    ToolAdapter.fromChimpTool(tool) shouldBe Tool(
      name = "noop",
      description = "",
      inputSchema = ToolInputSchema(
        `type` = "object",
        properties = Map.empty,
        required = None
      )
    )
  }

  it should "default a property with a missing type to string" in {
    val tool = ToolDefinition(
      name = "echo",
      description = None,
      inputSchema = Json.obj(
        "properties" -> Json.obj("message" -> Json.obj())
      )
    )

    ToolAdapter
      .fromChimpTool(tool)
      .asInstanceOf[Tool.Custom]
      .inputSchema
      .properties("message") shouldBe PropertySchema(`type` = "string")
  }
