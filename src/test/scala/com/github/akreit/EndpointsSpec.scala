package com.github.akreit

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.github.akreit.model.{ClientRequest, ContextSource}
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import com.github.akreit.server.Endpoints
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.client4.{UriContext, basicRequest}
import sttp.client4.testing.BackendStub
import sttp.tapir.integ.cats.effect.CatsMonadError
import sttp.tapir.server.stub4.TapirStubInterpreter

class EndpointsSpec extends AnyFlatSpec with Matchers with EitherValues:

  "client request json codec" should "decode additionalSources from plain strings" in {
    val request = readFromString[ClientRequest](
      """{
        |  "userId": "string",
        |  "message": "string",
        |  "additionalSources": ["Github"],
        |  "timestamp": 0
        |}""".stripMargin
    )

    request.additionalSources shouldBe List(ContextSource.Github)
  }

  "context endpoint" should "accept a JSON request and return a completion response" in {
    val backendStub =
      TapirStubInterpreter(BackendStub[IO](new CatsMonadError[IO]()))
        .whenServerEndpointRunLogic(Endpoints.contextServerEndpoint)
        .backend()

    val response = basicRequest
      .post(uri"http://test.com/v1/context/completions")
      .contentType("application/json")
      .body(
        """{
          |  "userId": "sess-123",
          |  "message": "What open PRs are blocking the release?",
          |  "additionalSources": ["Github", "Confluence"],
          |  "timestamp": 1715817600000
          |}""".stripMargin
      )
      .send(backendStub)

    response.map { result =>
      result.code.code shouldBe 200
      val body = result.body.value
      body should include("\"sessionId\":\"sess-123\"")
      body should include("\"finishReason\":\"stop\"")
      body should include("Enabled context sources: Github, Confluence")
    }.unwrap
  }

  extension [T](t: IO[T]) def unwrap: T = t.unsafeRunSync()
