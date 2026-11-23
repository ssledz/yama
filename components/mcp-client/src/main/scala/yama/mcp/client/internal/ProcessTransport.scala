package yama.mcp.client.internal

import cats.effect.{Async, Deferred, Resource}
import cats.effect.std.{Dispatcher, Queue}
import cats.effect.syntax.all.*
import cats.syntax.all.*
import fs2.{Stream, text}
import fs2.io.file.Path
import fs2.io.process.{ProcessBuilder, Processes}
import io.modelcontextprotocol.json.{McpJsonDefaults, McpJsonMapper, TypeRef}
import io.modelcontextprotocol.spec.{McpClientTransport, McpSchema}
import io.modelcontextprotocol.spec.McpSchema.JSONRPCMessage
import org.typelevel.log4cats.LoggerFactory
import reactor.core.publisher.Mono
import yama.mcp.client.McpServer

import java.util.function.Function

/** The SDK's client-transport interface on top of an fs2 process spawned from Scala.
  * Outgoing JSON-RPC messages: one line each, to the child's stdin. Incoming: the child's stdout, line by line.
  */
private[client] final class ProcessTransport[F[_]: Async](
    outbound: Queue[F, String],
    inboundHandler: Deferred[F, Function[Mono[JSONRPCMessage], Mono[JSONRPCMessage]]],
    mapper: McpJsonMapper,
    dispatcher: Dispatcher[F],
    /** Completes with a reason once the process has exited or its stdout has closed: the transport is dead then. */
    val exited: F[String],
    /** The same, without waiting: `Some(reason)` once dead. */
    val dead: F[Option[String]]
) extends McpClientTransport:

  private def toMono(fa: F[Unit]): Mono[Void] =
    Mono.fromFuture(() => dispatcher.unsafeToCompletableFuture(fa)).`then`()

  override def connect(handler: Function[Mono[JSONRPCMessage], Mono[JSONRPCMessage]]): Mono[Void] =
    toMono(inboundHandler.complete(handler).void)

  override def sendMessage(message: JSONRPCMessage): Mono[Void] =
    toMono(Async[F].delay(mapper.writeValueAsString(message)).flatMap(outbound.offer))

  // The process lifecycle belongs to the Resource, not to the SDK.
  override def closeGracefully(): Mono[Void] = Mono.empty()

  override def unmarshalFrom[T](data: Object, typeRef: TypeRef[T]): T = mapper.convertValue(data, typeRef)

private[client] object ProcessTransport:

  def spawn[F[_]: Async: Processes: LoggerFactory](server: McpServer.Local): Resource[F, ProcessTransport[F]] =
    val builder = ProcessBuilder(server.command, server.args).withExtraEnv(server.env)
    for
      stderrLog  <- Resource.eval(LoggerFactory[F].fromName(s"yama.mcp.${server.name}"))
      process    <- server.cwd.fold(builder)(dir => builder.withWorkingDirectory(Path.fromNioPath(dir))).spawn[F]
      dispatcher <- Dispatcher.parallel[F](await = false)
      outbound   <- Resource.eval(Queue.unbounded[F, String])
      handler    <- Resource.eval(Deferred[F, Function[Mono[JSONRPCMessage], Mono[JSONRPCMessage]]])
      stdoutEof  <- Resource.eval(Deferred[F, Unit])
      death      <- Resource.eval(Deferred[F, String])
      mapper      = McpJsonDefaults.getMapper()
      // stdin: one JSON-RPC message per line
      _ <- Stream.fromQueueUnterminated(outbound).map(_ + "\n").through(text.utf8.encode)
             .through(process.stdin).compile.drain.background
      // stdout: one JSON-RPC message per line, handed to the SDK
      _ <- process.stdout.through(text.utf8.decode).through(text.lines).filter(_.trim.nonEmpty)
             .evalMap { line =>
               Async[F].delay(McpSchema.deserializeJsonRpcMessage(mapper, line)).attempt.flatMap {
                 case Right(message) => handler.get.flatMap(h => Async[F].delay(h.apply(Mono.just(message)).subscribe()).void)
                 case Left(_)        => stderrLog.warn(s"[${server.name}] not JSON-RPC on stdout: $line")
               }
             }.compile.drain.guarantee(stdoutEof.complete(()).void).background
      // stderr: the server's own logs stay visible
      _ <- process.stderr.through(text.utf8.decode).through(text.lines).filter(_.trim.nonEmpty)
             .evalMap(line => stderrLog.info(s"[${server.name}] $line")).compile.drain.background
      // death: whichever comes first, the exit code or the end of stdout (a hung-up pipe is as dead as an exit)
      _ <- Async[F].race(process.exitValue, stdoutEof.get)
             .map {
               case Left(code) => s"exited with code $code"
               case Right(_)   => "closed its stdout"
             }
             .flatMap(death.complete).background
    yield ProcessTransport(outbound, handler, mapper, dispatcher, death.get, death.tryGet)
