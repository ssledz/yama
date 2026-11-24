package yama.config

import cats.effect.Sync
import io.circe.Json
import io.circe.parser.parse

import java.io.File
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path, Paths}

/** First run: write a config to `~/.yama/config.json` from the defaults bundled in the jar.
  *
  * The bundled config is the repo's `config.json`, whose files server runs from a checkout (`cwd: mcp/files-mcp`).
  * Outside a checkout that folder doesn't exist, so when `npx` is available the files server is switched to the
  * published package instead: `npx -y --package @ssledz/files-mcp@<version> files-mcp`, the version built
  * alongside this yama. The bin is named explicitly rather than left to npx to infer.
  */
object Bootstrap:
  val DefaultConfig = "yama/default-config.json"
  val FilesPackage  = "yama/files-mcp.package"
  val FilesServer   = "files"

  enum Outcome:
    case Existing             // left alone
    case Written(npx: Boolean) // created; `npx`: the files server runs from npm

  def write[F[_]: Sync](file: Path, npxAvailable: Boolean): F[Outcome] =
    Sync[F].blocking {
      if Files.exists(file) then Outcome.Existing
      else
        val bundled = resource(DefaultConfig)
        val content =
          if !npxAvailable then bundled
          else
            resource(FilesPackage).linesIterator.map(_.trim).filter(_.nonEmpty).toList match
              case pkg :: bin :: _ => viaNpx(bundled, pkg, bin)
              case other           => throw IllegalStateException(s"$FilesPackage is malformed: $other")
        Files.createDirectories(file.getParent)
        Files.writeString(file, content)
        Outcome.Written(npxAvailable)
    }

  /** The files server as an npx command: same env, no `cwd`. Other servers and settings are kept as they are. */
  def viaNpx(config: String, pkg: String, bin: String): String =
    val json   = parse(config).fold(e => throw IllegalStateException(s"bundled config is not JSON: ${e.message}"), identity)
    val server = json.hcursor.downField("mcpServers").downField(FilesServer)
    val env    = server.downField("env").focus.getOrElse(Json.obj())
    val npx    = Json.obj("command" -> Json.fromString("npx"), "args" -> Json.arr(List("-y", "--package", pkg, bin).map(Json.fromString)*), "env" -> env)
    server.withFocus(_ => npx).top.getOrElse(json).spaces2 + "\n"

  /** `npx` (or `npx.cmd` on Windows) somewhere on `PATH`. */
  def npxOnPath(path: Option[String]): Boolean =
    path.toList.flatMap(_.split(File.pathSeparator)).filter(_.nonEmpty).exists { dir =>
      List("npx", "npx.cmd").exists(name => Files.isExecutable(Paths.get(dir).resolve(name)))
    }

  private def resource(name: String): String =
    val in = Option(getClass.getClassLoader.getResourceAsStream(name)).getOrElse(throw IllegalStateException(s"$name is not on the classpath"))
    try String(in.readAllBytes(), UTF_8)
    finally in.close()
