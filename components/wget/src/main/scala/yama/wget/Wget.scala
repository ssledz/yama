package yama.wget

import cats.effect.Async
import cats.syntax.all.*
import fs2.io.file.{Files, Path as FPath}
import io.circe.{Decoder, Json}
import io.circe.syntax.*
import org.http4s.{Request, Uri}
import org.http4s.client.Client
import org.http4s.client.middleware.FollowRedirect
import org.http4s.headers.`Content-Type`
import org.typelevel.log4cats.Logger
import yama.tool.{Tool, ToolSchema}
import yama.wget.internal.Sandbox

import java.nio.file.Path

final case class WgetArgs(url: String, outputPath: String) derives ToolSchema, Decoder

/** Native tool: download a URL into the sandbox. "Native" = runs in this process instead of behind MCP. */
object Wget:
  /** The names the model addresses `roots` by, e.g. `List(/a/yama, /b/x)` → `List(yama, x)`. Same rule as files-mcp,
    * so pass the roots in the same order as its `FS_ROOTS`.
    */
  def mountNames(roots: List[Path]): List[String] = Sandbox.mountNames(roots)

  /** `roots`: the directories it may write to. A path without a mount prefix lands in the first one. */
  def make[F[_]: Async: Files: Logger](client: Client[F], roots: List[Path]): Tool[F] =
    val sandbox = Sandbox.of(roots)
    val inside  = sandbox.names.map(n => s"'$n/'").mkString(" or ")
    val http    = FollowRedirect(maxRedirects = 5)(client)
    Tool[F](
      name = "wget",
      description = "Download a resource over HTTP GET and save it into the sandbox. " +
        "Works for any file type: html, markdown, json, images, pdf.",
      schema = ToolSchema[WgetArgs].describe(
        "url"        -> "URL to download, e.g. 'https://example.com/report.pdf'",
        "outputPath" -> s"Where to save it, e.g. '${sandbox.names.head}/downloads/report.pdf'. Must stay inside $inside."
      ),
      strict = true
    ) { json =>
      for
        args   <- json.as[WgetArgs].liftTo[F]
        result <- sandbox.resolve(args.outputPath).fold(Tool.error(_).pure[F], download(http, sandbox, args.url, _))
      yield result
    }

  private def download[F[_]: Async: Files: Logger](client: Client[F], sandbox: Sandbox, url: String, target: Path): F[Json] =
    val file = FPath.fromNioPath(target)
    for
      uri  <- Uri.fromString(url).liftTo[F]
      _    <- Logger[F].debug(s"GET $url -> $target")
      _    <- file.parent.traverse_(Files[F].createDirectories)
      kind <- client.run(Request[F](uri = uri)).use { response =>
        if response.status.isSuccess then
          response.body.through(Files[F].writeAll(file)).compile.drain
            .as(response.headers.get[`Content-Type`].fold("unknown")(_.mediaType.toString).asRight[Int])
        else response.status.code.asLeft[String].pure[F]
      }
      out <- kind match
        case Left(status) => Tool.error(s"GET $url failed with status $status").pure[F]
        case Right(contentType) =>
          Files[F].size(file).map { bytes =>
            Json.obj(
              "success"      -> true.asJson,
              "url"          -> url.asJson,
              "output_path"  -> sandbox.display(target).asJson,
              "bytes"        -> bytes.asJson,
              "content_type" -> contentType.asJson
            )
          }
    yield out
