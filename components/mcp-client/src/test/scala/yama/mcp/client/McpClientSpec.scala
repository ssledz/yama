package yama.mcp.client

import cats.effect.{IO, Ref}
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.LoggerFactory
import org.typelevel.log4cats.noop.NoOpFactory

import java.nio.file.{Files, Path}
import scala.concurrent.duration.*
import scala.jdk.OptionConverters.*

/** Integration: spawns the vendored files-mcp server (needs `npm install --prefix mcp/files-mcp`). */
class McpClientSpec extends CatsEffectSuite:
  given LoggerFactory[IO] = NoOpFactory[IO]
  override def munitIOTimeout: Duration = 90.seconds

  private val serverDir = Path.of(sys.props("yama.root")).resolve("mcp/files-mcp")

  private def server(root: Path) =
    McpServer.Local("files", "npx", List("tsx", "src/index.ts"), Some(serverDir), Map("FS_ROOT" -> root.toString))

  private def children: Set[Long] =
    ProcessHandle.current.descendants.toArray.collect { case p: ProcessHandle => p.pid }.toSet

  test("lists tools, calls fs_read, and kills the process on release") {
    assume(Files.isDirectory(serverDir.resolve("node_modules")), "run npm install --prefix mcp/files-mcp")
    val root = Files.createDirectories(Files.createTempDirectory("yama-mcp").resolve("sandbox"))
    Files.writeString(root.resolve("hello.md"), "# Hello from the sandbox\n")
    for
      spawned <- Ref.of[IO, Set[Long]](Set.empty)
      before   = children
      (names, read) <- McpClient.connect[IO](server(root)).use { client =>
        for
          _     <- IO(children -- before).flatMap(spawned.set)
          tools <- client.listTools
          read  <- client.callTool("fs_read", Json.obj("path" -> "sandbox/hello.md".asJson))
        yield (tools.map(_.name), read)
      }
      started <- spawned.get
      _       <- IO.sleep(500.millis)
    yield
      assert(names.contains("fs_read"), names)
      assert(names.contains("fs_write"), names)
      assert(read.noSpaces.contains("Hello from the sandbox"), read.noSpaces)
      assert(started.nonEmpty, "the server process was spawned")
      val alive = started.filter(pid => ProcessHandle.of(pid).toScala.exists(_.isAlive))
      assertEquals(alive, Set.empty[Long], "no leaked child processes")
  }
