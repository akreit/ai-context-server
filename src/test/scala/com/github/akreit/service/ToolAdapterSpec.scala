package com.github.akreit.service

import scala.jdk.CollectionConverters.*

import io.circe.Json
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ToolAdapterSpec extends AnyFlatSpec with Matchers:

  "ujsonToJavaArg" should "convert a string without JSON-quoting" in {
    ToolAdapter.ujsonToJavaArg(
      Json.fromString("user:akreit")
    ) shouldBe "user:akreit"
  }

  it should "convert a number to java.lang.Double" in {
    ToolAdapter.ujsonToJavaArg(
      Json.fromDoubleOrNull(42.5)
    ) shouldBe java.lang.Double.valueOf(42.5)
  }

  it should "convert true to java.lang.Boolean" in {
    ToolAdapter.ujsonToJavaArg(
      Json.fromBoolean(true)
    ) shouldBe java.lang.Boolean.TRUE
  }

  it should "convert false to java.lang.Boolean" in {
    ToolAdapter.ujsonToJavaArg(
      Json.fromBoolean(false)
    ) shouldBe java.lang.Boolean.FALSE
  }

  it should "convert null to null" in {
    ToolAdapter.ujsonToJavaArg(Json.Null) shouldBe null
  }

  it should "convert an array to a java.util.List" in {
    val result =
      ToolAdapter.ujsonToJavaArg(
        Json.arr(Json.fromString("a"), Json.fromDoubleOrNull(1))
      )
    result shouldBe List("a", java.lang.Double.valueOf(1)).asJava
  }

  it should "convert an object to a java.util.Map" in {
    val result =
      ToolAdapter.ujsonToJavaArg(Json.obj("key" -> Json.fromString("val")))
    result shouldBe Map("key" -> "val").asJava
  }

  it should "recursively convert nested structures" in {
    val input = Json.obj(
      "query" -> Json.fromString("user:akreit"),
      "limit" -> Json.fromDoubleOrNull(10),
      "tags" -> Json.arr(Json.fromString("x"), Json.fromString("y"))
    )
    val result = ToolAdapter
      .ujsonToJavaArg(input)
      .asInstanceOf[java.util.Map[String, AnyRef]]
    result.get("query") shouldBe "user:akreit"
    result.get("limit") shouldBe java.lang.Double.valueOf(10)
    result.get("tags") shouldBe List("x", "y").asJava
  }
