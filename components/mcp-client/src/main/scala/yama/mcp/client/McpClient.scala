package yama.mcp.client

import cats.effect.{Async, Resource}
import fs2.io.process.Processes
import io.circe.Json
import org.typelevel.log4cats.LoggerFactory
import yama.mcp.client.internal.{ProcessTransport, RemoteTransport, SdkMcpClient, Supervisor}

import scala.concurrent.duration.*

/** A tool as an MCP server advertises it. The schema is plain circe `Json`: no Jackson leaks out. */
final case class McpTool(name: String, description: String, inputSchema: Json)

/** Where an MCP server lives: a local process spoken to over stdio, or a remote streamable-HTTP endpoint. */
enum McpServer:
  def name: String
  case Local(name: String, command: String, args: List[String], cwd: Option[java.nio.file.Path], env: Map[String, String])
  case Remote(name: String, url: String, headers: Map[String, String])

/** How a crashed local server is brought back. Backoff doubles per consecutive failure, from `initialBackoff` up to
  * `maxBackoff`. A process that dies before `stableAfter` counts as a failure; after `maxRestarts` of those in a row
  * the client gives up and fails every call fast. One that ran for `stableAfter` resets the count.
  */
final case class RestartPolicy(
    maxRestarts: Int = 5,
    initialBackoff: FiniteDuration = 500.millis,
    maxBackoff: FiniteDuration = 30.seconds,
    stableAfter: FiniteDuration = 1.minute
)

object RestartPolicy:
  val default: RestartPolicy = RestartPolicy()

/** The server's process died (mid-call, or for good after too many restarts). */
final class McpServerUnavailable(val server: String, reason: String)
    extends RuntimeException(s"MCP server '$server' $reason")

/** A thin Cats Effect wrapper over the official MCP Java SDK. */
trait McpClient[F[_]]:
  def server: String
  def listTools: F[List[McpTool]]
  /** The tool's text content, parsed as JSON when it is JSON. A tool error becomes `{"error": ...}`. */
  def callTool(name: String, args: Json): F[Json]

object McpClient:
  /** Local servers are spawned (and killed on release), and respawned per `restart` when their process dies;
    * `initialize` on every acquire, `closeGracefully` on release.
    */
  def connect[F[_]: Async: Processes: LoggerFactory](
      server: McpServer,
      restart: RestartPolicy = RestartPolicy.default
  ): Resource[F, McpClient[F]] =
    server match
      case local: McpServer.Local =>
        val session = ProcessTransport.spawn(local).flatMap { transport =>
          SdkMcpClient.make(local.name, transport).map(Supervisor.Session(_, transport.exited, transport.dead))
        }
        Resource.eval(LoggerFactory[F].fromName(s"yama.mcp.${local.name}"))
          .flatMap(Supervisor.make(local.name, session, restart, _))
      case remote: McpServer.Remote => RemoteTransport.make(remote).flatMap(SdkMcpClient.make(remote.name, _))
