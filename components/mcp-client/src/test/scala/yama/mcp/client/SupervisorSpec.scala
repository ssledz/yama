package yama.mcp.client

import cats.effect.{Deferred, IO, Ref, Resource}
import cats.syntax.all.*
import io.circe.Json
import munit.CatsEffectSuite
import org.typelevel.log4cats.noop.NoOpLogger
import yama.mcp.client.internal.Supervisor

import scala.concurrent.duration.*

/** The restart logic against fake sessions: no processes involved. */
class SupervisorSpec extends CatsEffectSuite:

  private val fast = RestartPolicy(maxRestarts = 2, initialBackoff = 10.millis, maxBackoff = 50.millis, stableAfter = 1.minute)

  /** A fake server: generation `n` answers `n`; `kill` makes the current one die; `hang` makes calls never return. */
  private final class Fake(spawned: Ref[IO, Int], deaths: Ref[IO, List[Deferred[IO, String]]], hang: Boolean = false):
    def spawn: Resource[IO, Supervisor.Session[IO]] = Resource.eval {
      for
        n     <- spawned.updateAndGet(_ + 1)
        death <- Deferred[IO, String]
        _     <- deaths.update(death :: _)
      yield Supervisor.Session(client(n), death.get, death.tryGet)
    }
    def kill: IO[Unit] = deaths.get.flatMap(_.head.complete("exited with code 137").void)

    private def client(n: Int): McpClient[IO] = new McpClient[IO]:
      def server: String                                   = "fake"
      def listTools: IO[List[McpTool]]                     = IO.pure(Nil)
      def callTool(name: String, args: Json): IO[Json]     = if hang then IO.never else IO.pure(Json.fromInt(n))

  private def fake(hang: Boolean = false) =
    (Ref.of[IO, Int](0), Ref.of[IO, List[Deferred[IO, String]]](Nil)).mapN((s, d) => (s, Fake(s, d, hang)))

  private def supervised(f: Fake, policy: RestartPolicy = fast) =
    Supervisor.make[IO]("fake", f.spawn, policy, NoOpLogger[IO])

  test("a dead server is respawned and later calls reach the new one") {
    fake().flatMap { (spawned, f) =>
      supervised(f).use { client =>
        for
          first  <- client.callTool("t", Json.Null)
          _      <- f.kill
          second <- client.callTool("t", Json.Null)
          n      <- spawned.get
        yield
          assertEquals(first, Json.fromInt(1))
          assertEquals(second, Json.fromInt(2))
          assertEquals(n, 2)
      }
    }
  }

  test("a call in flight when the server dies fails at once, it does not hang") {
    fake(hang = true).flatMap { (_, f) =>
      supervised(f).use { client =>
        for
          call   <- client.callTool("t", Json.Null).attempt.start
          _      <- IO.sleep(20.millis) *> f.kill
          result <- call.joinWithNever.timeout(2.seconds)
        yield result match
          case Left(e: McpServerUnavailable) => assert(e.getMessage.contains("exited with code 137"), e.getMessage)
          case other                         => fail(s"expected McpServerUnavailable, got $other")
      }
    }
  }

  test("a crash loop gives up after maxRestarts and then fails fast") {
    fake().flatMap { (spawned, f) =>
      supervised(f).use { client =>
        for
          _      <- (client.callTool("t", Json.Null) *> f.kill).replicateA_(fast.maxRestarts + 1)
          result <- client.callTool("t", Json.Null).attempt.timeout(2.seconds)
          n      <- spawned.get
        yield
          assert(result.left.exists(_.getMessage.contains("gave up")), result)
          assertEquals(n, fast.maxRestarts + 1)
      }
    }
  }

  test("a process that ran past stableAfter resets the failure count") {
    fake().flatMap { (spawned, f) =>
      supervised(f, fast.copy(stableAfter = Duration.Zero)).use { client =>
        for
          _ <- (client.callTool("t", Json.Null) *> f.kill).replicateA_(fast.maxRestarts + 3)
          r <- client.callTool("t", Json.Null)
        yield assertEquals(r, Json.fromInt(fast.maxRestarts + 4))
      }
    }
  }

  test("shutting down is not a crash: nothing is respawned on release") {
    fake().flatMap { (spawned, f) =>
      supervised(f).use(_.callTool("t", Json.Null)) *> f.kill *> IO.sleep(100.millis) *>
        spawned.get.map(assertEquals(_, 1))
    }
  }
