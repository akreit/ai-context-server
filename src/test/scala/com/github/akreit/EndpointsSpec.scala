package com.github.akreit

import cats.effect.IO
import cats.effect.unsafe.implicits.global
import com.github.akreit.model.*
import com.github.akreit.server.Endpoints
import com.github.akreit.service.LlmGateway
import com.github.akreit.service.LlmResult
import com.github.plokhotnyuk.jsoniter_scala.core.readFromString
import org.scalatest.EitherValues
import org.scalatest.flatspec.AnyFlatSpec
import org.scalatest.matchers.should.Matchers
import sttp.ai.claude.models.ContentBlock
import sttp.ai.claude.models.Usage as ClaudeUsage
import sttp.ai.claude.responses.MessageResponse
import sttp.client4.UriContext
import sttp.client4.basicRequest
import sttp.client4.testing.BackendStub
import sttp.tapir.integ.cats.effect.CatsMonadError
import sttp.tapir.server.stub4.TapirStubInterpreter

class EndpointsSpec extends AnyFlatSpec with Matchers with EitherValues:

  // LlmGateway is a SAM (Single Abstract Method) trait, so we can implement it with a lambda for testing;
  private val stubGateway: LlmGateway = (_: ClientRequest) =>
    IO.pure(
      Right(
        LlmResult(
          response = MessageResponse(
            id = "msg-stub",
            `type` = "message",
            role = "assistant",
            content = List(
              ContentBlock.Text("This is some response from the llm")
            ),
            model = "claude-sonnet-4-20250514",
            stopReason = Some("end_turn"),
            stopSequence = None,
            usage = ClaudeUsage(inputTokens = 10, outputTokens = 20)
          ),
          toolCallsMade = Nil
        )
      )
    )

  private val endpoints = Endpoints(stubGateway)

  private def sendRequest(json: String): IO[CompletionResponse] =
    val backendStub =
      TapirStubInterpreter(BackendStub[IO](new CatsMonadError[IO]()))
        .whenServerEndpointRunLogic(endpoints.contextServerEndpoint)
        .backend()

    basicRequest
      .post(uri"http://test.com/v1/context/completions")
      .contentType("application/json")
      .body(json)
      .send(backendStub)
      .map { result =>
        result.code.code shouldBe 200
        readFromString[CompletionResponse](result.body.value)
      }

  "client request json codec" should "decode additionalSources from plain strings" in {
    val request = readFromString[ClientRequest](
      """{
        |  "userId": "string",
        |  "message": "string",
        |  "additionalSources": ["GitHub", "Jira"],
        |  "timestamp": 0
        |}""".stripMargin
    )
    request.additionalSources shouldBe Some(
      List(
        ContextSource.GitHub,
        ContextSource.Jira
      )
    )
  }

  it should "decode case-insensitive context sources" in {
    pendingUntilFixed {
      val request = readFromString[ClientRequest](
        """{
          |  "userId": "u1",
          |  "message": "hi",
          |  "additionalSources": ["github", "jira", "CONFLUENCE"],
          |  "timestamp": 0
          |}""".stripMargin
      )
      request.additionalSources shouldBe List(
        ContextSource.GitHub,
        ContextSource.Jira,
        ContextSource.Confluence
      )
    }
  }

  "context endpoint" should "return 200 with the stubbed LLM content" in {
    sendRequest(
      """{
        |  "userId": "sess-123",
        |  "message": "What open PRs are blocking the release?",
        |  "additionalSources": ["GitHub", "Confluence"],
        |  "timestamp": 1715817600000
        |}""".stripMargin
    ).map { response =>
      response.sessionId shouldBe "sess-123"
      response.id shouldBe "cmpl-1715817600000"
      response.model shouldBe "claude-sonnet-4-20250514"
      response.provider shouldBe "anthropic"
      response.finishReason shouldBe "end_turn"
      response.message shouldBe AssistantMessage(
        role = "assistant",
        content = "This is some response from the llm"
      )
    }.unwrap
  }

  it should "return correct token usage from the LLM response" in {
    sendRequest(
      """{
        |  "userId": "u1",
        |  "message": "hello",
        |  "additionalSources": [],
        |  "timestamp": 0
        |}""".stripMargin
    ).map { response =>
      response.usage shouldBe Usage(
        inputTokens = 10,
        outputTokens = 20,
        totalTokens = 30,
        toolRounds = 0
      )
    }.unwrap
  }

  it should "return an empty toolCallsMade list" in {
    sendRequest(
      """{
        |  "userId": "u1",
        |  "message": "hi",
        |  "additionalSources": ["Jira"],
        |  "timestamp": 0
        |}""".stripMargin
    ).map { response =>
      response.toolCallsMade shouldBe List.empty
    }.unwrap
  }

  extension [T](t: IO[T]) def unwrap: T = t.unsafeRunSync()
