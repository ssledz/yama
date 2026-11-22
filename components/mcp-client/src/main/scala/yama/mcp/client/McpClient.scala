package yama.mcp.client

import cats.effect.{Async, Resource}
import fs2.io.process.Processes
import io.circe.Json
import org.typelevel.log4cats.LoggerFactory
import yama.mcp.client.internal.{ProcessTransport, RemoteTransport, SdkMcpClient}

/** A tool as an MCP server advertises it. The schema is plain circe `Json`: no Jackson leaks out. */
final case class McpTool(name: String, description: String, inputSchema: Json)

/** Where an MCP server lives: a local process spoken to over stdio, or a remote streamable-HTTP endpoint. */
enum McpServer:
  def name: String
  case Local(name: String, command: String, args: List[String], cwd: Option[java.nio.file.Path], env: Map[String, String])
  case Remote(name: String, url: String, headers: Map[String, String])

/** A thin Cats Effect wrapper over the official MCP Java SDK. */
trait McpClient[F[_]]:
  def server: String
  def listTools: F[List[McpTool]]
  /** The tool's text content, parsed as JSON when it is JSON. A tool error becomes `{"error": ...}`. */
  def callTool(name: String, args: Json): F[Json]

object McpClient:
  /** Local servers are spawned (and killed on release); `initialize` on acquire, `closeGracefully` on release. */
  def connect[F[_]: Async: Processes: LoggerFactory](server: McpServer): Resource[F, McpClient[F]] =
    server match
      case local: McpServer.Local   => ProcessTransport.spawn(local).flatMap(SdkMcpClient.make(local.name, _))
      case remote: McpServer.Remote => RemoteTransport.make(remote).flatMap(SdkMcpClient.make(remote.name, _))
