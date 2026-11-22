package yama.mcp.client.internal

import cats.effect.{Async, Resource, Sync}
import cats.syntax.all.*
import io.circe.Json
import io.modelcontextprotocol.client.{McpAsyncClient, McpClient as JMcpClient}
import io.modelcontextprotocol.spec.McpClientTransport
import io.modelcontextprotocol.spec.McpSchema.{CallToolRequest, Implementation, TextContent}
import reactor.core.publisher.Mono
import yama.mcp.client.{McpClient, McpTool}

import java.time.Duration
import scala.jdk.CollectionConverters.*

/** `McpAsyncClient` (Reactor `Mono`s, Jackson types) → `McpClient[F]` (F, circe `Json`). */
private[client] object SdkMcpClient:

  /** Any Java async library can be wrapped this way. */
  def fromMono[F[_]: Async, A](mono: => Mono[A]): F[A] =
    Async[F].fromCompletableFuture(Sync[F].delay(mono.toFuture))

  def make[F[_]: Async](name: String, transport: McpClientTransport): Resource[F, McpClient[F]] =
    Resource
      .make(Sync[F].delay {
        JMcpClient
          .async(transport)
          .clientInfo(Implementation.builder("yama", "0.1.0").build())
          .requestTimeout(Duration.ofSeconds(120))
          .initializationTimeout(Duration.ofSeconds(60))
          .build()
      })(client => fromMono(client.closeGracefully()).void.handleError(_ => ()))
      .evalTap(client => fromMono(client.initialize()))
      .map(wrap(name, _))

  private def wrap[F[_]: Async](name: String, client: McpAsyncClient): McpClient[F] = new McpClient[F]:
    def server: String = name

    def listTools: F[List[McpTool]] =
      fromMono(client.listTools()).flatMap { result =>
        result.tools.asScala.toList.traverse { tool =>
          JsonBridge.toCirce[F](tool.inputSchema).map(McpTool(tool.name, Option(tool.description).getOrElse(""), _))
        }
      }

    def callTool(tool: String, args: Json): F[Json] =
      for
        arguments <- JsonBridge.toJavaMap[F](args)
        result    <- fromMono(client.callTool(CallToolRequest(tool, arguments, null)))
        texts      = result.content.asScala.collect { case t: TextContent => t.text }.mkString("\n")
        output    <-
          if Boolean.box(true) == result.isError then Json.obj("error" -> Json.fromString(texts)).pure[F]
          else if texts.nonEmpty then io.circe.parser.parse(texts).getOrElse(Json.fromString(texts)).pure[F]
          else Option(result.structuredContent).fold(Json.Null.pure[F])(JsonBridge.toCirce[F](_))
      yield output
