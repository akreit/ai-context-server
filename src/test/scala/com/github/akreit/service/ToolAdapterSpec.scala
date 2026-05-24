package com.github.akreit.service

import scala.jdk.CollectionConverters.*

import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers

class ToolAdapterSpec extends AnyFlatSpec with Matchers:

  "ujsonToJavaArg" should "convert a string without JSON-quoting" in {
    ToolAdapter.ujsonToJavaArg(ujson.Str("user:akreit")) shouldBe "user:akreit"
  }

  it should "convert a number to java.lang.Double" in {
    ToolAdapter.ujsonToJavaArg(ujson.Num(42.5)) shouldBe java.lang.Double.valueOf(42.5)
  }

  it should "convert true to java.lang.Boolean" in {
    ToolAdapter.ujsonToJavaArg(ujson.Bool(true)) shouldBe java.lang.Boolean.TRUE
  }

  it should "convert false to java.lang.Boolean" in {
    ToolAdapter.ujsonToJavaArg(ujson.Bool(false)) shouldBe java.lang.Boolean.FALSE
  }

  it should "convert null to null" in {
    ToolAdapter.ujsonToJavaArg(ujson.Null) shouldBe null
  }

  it should "convert an array to a java.util.List" in {
    val result = ToolAdapter.ujsonToJavaArg(ujson.Arr(ujson.Str("a"), ujson.Num(1)))
    result shouldBe List("a", java.lang.Double.valueOf(1)).asJava
  }

  it should "convert an object to a java.util.Map" in {
    val result = ToolAdapter.ujsonToJavaArg(ujson.Obj("key" -> ujson.Str("val")))
    result shouldBe Map("key" -> "val").asJava
  }

  it should "recursively convert nested structures" in {
    val input = ujson.Obj(
      "query" -> ujson.Str("user:akreit"),
      "limit" -> ujson.Num(10),
      "tags"  -> ujson.Arr(ujson.Str("x"), ujson.Str("y"))
    )
    val result = ToolAdapter.ujsonToJavaArg(input).asInstanceOf[java.util.Map[String, AnyRef]]
    result.get("query") shouldBe "user:akreit"
    result.get("limit") shouldBe java.lang.Double.valueOf(10)
    result.get("tags") shouldBe List("x", "y").asJava
  }