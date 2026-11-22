package yama.chat.openai

import cats.effect.{IO, Ref}
import ciris.Secret
import io.circe.Json
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.typelevel.ci.*
import yama.chat.ChatError
import yama.conversation.Item

import scala.io.Source

/** The HTTP call against an in-memory provider: endpoint, auth and extra headers, status mapping. */
class OpenAiCompatSpec extends CatsEffectSuite:

  private val provider = ProviderConfig("test", "https://llm.example/v1/", Map("X-Title" -> "yama"))
  private val answer   = Source.fromResource("golden/openai-message.json").mkString

  /** An in-memory provider that remembers the request it got (with its body, read before the response). */
  private def server(status: Status, body: String): IO[(Client[IO], Ref[IO, Option[(Request[IO], Json)]])] =
    Ref.of[IO, Option[(Request[IO], Json)]](None).map { seen =>
      val app = HttpApp[IO] { req =>
        req.as[Json].flatMap(json => seen.set(Some(req -> json))) >> IO.pure(Response[IO](status).withEntity(body))
      }
      (Client.fromHttpApp(app), seen)
    }

  test("POSTs to {baseUrl}/responses with the bearer key and provider headers") {
    for
      (client, seen) <- server(Ok, answer)
      chat            = OpenAiCompat.make(client, provider, "gpt-4.1", Secret("sk-test"))
      reply          <- chat.send("be brief", Nil, Vector(Item.User("hi")))
      (request, body) <- seen.get.map(_.get)
    yield
      assertEquals(reply.text, Some("I saved a summary to sandbox/aos.md."))
      assertEquals(request.method, Method.POST)
      assertEquals(request.uri.renderString, "https://llm.example/v1/responses")
      assertEquals(request.headers.get(ci"Authorization").map(_.head.value), Some("Bearer sk-test"))
      assertEquals(request.headers.get(ci"X-Title").map(_.head.value), Some("yama"))
      assertEquals(body.hcursor.get[String]("model"), Right("gpt-4.1"))
  }

  test("maps 429 to a retryable error and 400 to a fatal one") {
    for
      (tooMany, _) <- server(TooManyRequests, """{"error":{"message":"slow down"}}""")
      (bad, _)     <- server(BadRequest, """{"error":{"message":"context too long"}}""")
      r1           <- OpenAiCompat.make(tooMany, provider, "m", Secret("k")).send("", Nil, Vector.empty).attempt
      r2           <- OpenAiCompat.make(bad, provider, "m", Secret("k")).send("", Nil, Vector.empty).attempt
    yield
      assertEquals(r1, Left(ChatError.Retryable(429, "slow down", None)))
      assertEquals(r2, Left(ChatError.Fatal(400, "context too long")))
  }
