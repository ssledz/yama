package yama.chat

import cats.effect.{IO, Ref}
import cats.effect.std.Random
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import yama.conversation.Item

import scala.concurrent.duration.*

class ChatRetryingSpec extends CatsEffectSuite:
  given Logger[IO] = NoOpLogger[IO]

  private val config = Chat.RetryPolicyConfig(maxAttempts = 3, baseDelay = 1.milli, maxDelay = 10.millis)
  private val ok     = Reply(Vector(Item.Assistant("ok")), Usage.zero)

  /** A chat that fails with the given errors first, then answers. */
  private def flaky(errors: List[Throwable]): IO[(Chat[IO], Ref[IO, Int])] =
    Ref.of[IO, Int](0).map { attempts =>
      val chat: Chat[IO] = (_, _, _) =>
        attempts.getAndUpdate(_ + 1).flatMap(n => errors.lift(n).fold(IO.pure(ok))(IO.raiseError))
      (chat, attempts)
    }

  private def retrying(chat: Chat[IO]): IO[Chat[IO]] =
    Random.scalaUtilRandom[IO].map { case given Random[IO] => Chat.retrying(config)(chat) }

  test("retries a retryable error until the call succeeds") {
    for
      (chat, attempts) <- flaky(List(ChatError.Retryable(429, "slow down", None), ChatError.Retryable(503, "busy", None)))
      reply            <- retrying(chat).flatMap(_.send("", Nil, Vector.empty))
      n                <- attempts.get
    yield
      assertEquals(reply, ok)
      assertEquals(n, 3)
  }

  test("gives up after maxAttempts") {
    val errors = List.fill(5)(ChatError.Retryable(429, "slow down", Some(1.milli)))
    for
      (chat, attempts) <- flaky(errors)
      result           <- retrying(chat).flatMap(_.send("", Nil, Vector.empty)).attempt
      n                <- attempts.get
    yield
      assert(result.left.exists(_.isInstanceOf[ChatError.Retryable]))
      assertEquals(n, 3)
  }

  test("never retries a fatal error") {
    for
      (chat, attempts) <- flaky(List(ChatError.Fatal(401, "bad key")))
      result           <- retrying(chat).flatMap(_.send("", Nil, Vector.empty)).attempt
      n                <- attempts.get
    yield
      assertEquals(result, Left(ChatError.Fatal(401, "bad key")))
      assertEquals(n, 1)
  }
