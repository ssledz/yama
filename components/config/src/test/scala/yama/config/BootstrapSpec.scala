package yama.config

import cats.effect.IO
import munit.CatsEffectSuite

import java.nio.file.{Files, Path}
import java.nio.file.attribute.PosixFilePermissions

class BootstrapSpec extends CatsEffectSuite:

  private def fresh: Path = Files.createTempDirectory("yama-home").resolve("nested/config.json")

  test("without npx: the bundled config verbatim, files server from a checkout; written once") {
    val file = fresh
    for
      first  <- Bootstrap.write[IO](file, npxAvailable = false)
      _      <- IO(Files.writeString(file, Files.readString(file) + " "))
      second <- Bootstrap.write[IO](file, npxAvailable = false)
      config <- Config.load[IO](file, Overrides())
    yield
      assertEquals(first, Bootstrap.Outcome.Written(npx = false))
      assertEquals(second, Bootstrap.Outcome.Existing)
      assert(Files.readString(file).endsWith(" "), "an existing file is not overwritten")
      assertEquals(config.mcpServers("files"), McpServerSettings.Local("npx", List("tsx", "src/index.ts"), Map("LOG_LEVEL" -> "info", "MAX_FILE_SIZE" -> "10485760"), Some("mcp/files-mcp")))
  }

  test("with npx: the files server runs the published package, pinned, same env; the rest is untouched") {
    val file = fresh
    for
      outcome <- Bootstrap.write[IO](file, npxAvailable = true)
      config  <- Config.load[IO](file, Overrides())
      repo    <- Config.load[IO](Path.of(sys.props("yama.root")).resolve("config.json"), Overrides())
    yield
      assertEquals(outcome, Bootstrap.Outcome.Written(npx = true))
      config.mcpServers("files") match
        case McpServerSettings.Local("npx", List("-y", "--package", pkg, "files-mcp"), env, None) =>
          assert(pkg.matches("""@ssledz/files-mcp@\d+\.\d+\.\d+.*"""), pkg)
          assertEquals(env.get("MAX_FILE_SIZE"), Some("10485760"))
        case other => fail(s"unexpected files server: $other")
      assertEquals(config.mcpServers("tavily"), repo.mcpServers("tavily"))
      assertEquals(config.agent, repo.agent)
      assertEquals(config.subagents, repo.subagents)
  }

  test("npx is found on PATH only as an executable") {
    val dir  = Files.createTempDirectory("bin")
    val npx  = Files.createFile(dir.resolve("npx"))
    val none = Files.createTempDirectory("empty")
    assert(!Bootstrap.npxOnPath(Some(dir.toString)), "not executable yet")
    Files.setPosixFilePermissions(npx, PosixFilePermissions.fromString("rwxr-xr-x"))
    assert(Bootstrap.npxOnPath(Some(s"$none${java.io.File.pathSeparator}$dir")))
    assert(!Bootstrap.npxOnPath(Some(none.toString)))
    assert(!Bootstrap.npxOnPath(None))
  }
