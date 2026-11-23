package yama.mcp.client.internal

import cats.effect.{Deferred, Ref, Resource, Temporal}
import cats.effect.std.NonEmptyHotswap
import cats.effect.syntax.all.*
import cats.syntax.all.*
import io.circe.Json
import org.typelevel.log4cats.Logger
import yama.mcp.client.{McpClient, McpServerUnavailable, McpTool, RestartPolicy}

import scala.concurrent.duration.*

/** Keeps a local MCP server alive: when its process dies, a fresh one is spawned (and initialized) in its place.
  *
  * Calls made while the server restarts wait for the new process. A call that is in flight when the process dies
  * fails at once (instead of hanging until the request timeout) and is not retried: the tool may not be idempotent.
  * A crash loop (a process that dies before `stableAfter`) counts against `maxRestarts`; after that the client fails
  * every call fast.
  */
private[client] object Supervisor:

  /** One live process: its client, a signal that completes once the process is dead, and the same check without waiting. */
  final case class Session[F[_]](client: McpClient[F], exited: F[String], dead: F[Option[String]])

  /** Generations form a chain: each one points at the slot its successor will fill. A caller holding a dead
    * generation just follows `next`, so there is no window in which a new call is sent to a dead process.
    */
  private final case class Generation[F[_]](session: Session[F], next: Slot[F])
  private type Slot[F[_]] = Deferred[F, Either[Throwable, Generation[F]]]
  private type Swap[F[_]] = NonEmptyHotswap[F, Option[Session[F]]]

  def make[F[_]: Temporal](
      name: String,
      spawn: Resource[F, Session[F]],
      policy: RestartPolicy,
      log: Logger[F]
  ): Resource[F, McpClient[F]] =
    for
      hotswap <- NonEmptyHotswap.empty[F, Session[F]]
      // the first spawn fails the whole Resource, as before: a server that never starts is a config error
      first   <- Resource.eval(generation(hotswap, spawn))
      head    <- Resource.eval(slot[F].flatTap(_.complete(Right(first))))
      current <- Resource.eval(Ref.of[F, Slot[F]](head))
      started <- Resource.eval(Temporal[F].monotonic)
      // acquired after the hotswap, so on release it stops before the process is killed: no restart on shutdown
      _       <- Watcher(name, spawn, policy, log, hotswap, current).watch(first, started, failures = 0).background
    yield client(name, current)

  private def slot[F[_]: Temporal]: F[Slot[F]] = Deferred[F, Either[Throwable, Generation[F]]]

  /** Spawn into the hotswap (releasing whatever was there) and hand back the new generation. */
  private def generation[F[_]: Temporal](hotswap: Swap[F], spawn: Resource[F, Session[F]]): F[Generation[F]] =
    for
      acquired <- Deferred[F, Session[F]]
      _        <- hotswap.swap(spawn.evalTap(acquired.complete).map(Some(_)))
      session  <- acquired.get
      next     <- slot[F]
    yield Generation(session, next)

  private final case class Watcher[F[_]: Temporal](
      name: String,
      spawn: Resource[F, Session[F]],
      policy: RestartPolicy,
      log: Logger[F],
      hotswap: Swap[F],
      current: Ref[F, Slot[F]]
  ):
    def watch(gen: Generation[F], startedAt: FiniteDuration, failures: Int): F[Unit] =
      for
        reason <- gen.session.exited
        _      <- current.set(gen.next) // new calls wait for the successor from here on
        _      <- log.warn(s"MCP server '$name' $reason; restarting")
        _      <- hotswap.clear
        now    <- Temporal[F].monotonic
        _      <- respawn(gen.next, if now - startedAt >= policy.stableAfter then 0 else failures + 1)
      yield ()

    private def respawn(next: Slot[F], failures: Int): F[Unit] =
      if failures > policy.maxRestarts then
        val error = McpServerUnavailable(name, s"gave up after ${policy.maxRestarts} restarts")
        log.error(error.getMessage) *> next.complete(Left(error)).void
      else
        val delay = (policy.initialBackoff * (1L << failures.min(20))).min(policy.maxBackoff)
        Temporal[F].sleep(delay) *> generation(hotswap, spawn).attempt.flatMap {
          case Right(fresh) =>
            log.info(s"MCP server '$name' restarted") *> next.complete(Right(fresh)) *>
              Temporal[F].monotonic.flatMap(watch(fresh, _, failures))
          case Left(e) =>
            log.warn(s"MCP server '$name' failed to restart: ${e.getMessage}") *> respawn(next, failures + 1)
        }

  private def client[F[_]: Temporal](name: String, current: Ref[F, Slot[F]]): McpClient[F] = new McpClient[F]:
    def server: String = name

    // Skip dead generations, then race the call against the death of the process it was sent to.
    private def onLive[A](call: McpClient[F] => F[A]): F[A] =
      def from(slot: Slot[F]): F[A] =
        slot.get.rethrow.flatMap { gen =>
          gen.session.dead.flatMap {
            case Some(_) => from(gen.next)
            case None =>
              Temporal[F].race(gen.session.exited, call(gen.session.client)).flatMap {
                case Right(a)     => a.pure[F]
                case Left(reason) =>
                  McpServerUnavailable(name, s"$reason during the call; it is being restarted, retry if the call is safe to repeat")
                    .raiseError[F, A]
              }
          }
        }
      current.get.flatMap(from)

    def listTools: F[List[McpTool]]                 = onLive(_.listTools)
    def callTool(tool: String, args: Json): F[Json] = onLive(_.callTool(tool, args))
