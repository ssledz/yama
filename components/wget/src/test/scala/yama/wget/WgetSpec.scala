package yama.wget

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.dsl.io.*
import org.http4s.headers.Location
import org.http4s.syntax.literals.uri
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import yama.tool.Tool

import java.nio.file.{Files, Path}

class WgetSpec extends CatsEffectSuite:
  given Logger[IO] = NoOpLogger[IO]

  private val web = Client.fromHttpApp[IO](HttpApp {
    case GET -> Root / "page.html" => Ok("<h1>hello</h1>")
    case GET -> Root / "moved"     => IO.pure(Response[IO](Status.Found).putHeaders(Location(uri"https://site.test/page.html")))
    case _                         => NotFound()
  })

  private val sandbox = FunFixture[Path](
    _ => Files.createDirectories(Files.createTempDirectory("yama-wget").resolve("sandbox")),
    _ => ()
  )

  private def args(url: String, out: String): Json = Json.obj("url" -> url.asJson, "outputPath" -> out.asJson)

  sandbox.test("downloads into the sandbox, addressed by mount name") { dir =>
    Wget.make[IO](web, List(dir)).run(args("https://site.test/page.html", "sandbox/downloads/page.html")).map { out =>
      assertEquals(out.hcursor.get[String]("output_path"), Right("sandbox/downloads/page.html"))
      assertEquals(out.hcursor.get[Long]("bytes"), Right(14L))
      assertEquals(Files.readString(dir.resolve("downloads/page.html")), "<h1>hello</h1>")
    }
  }

  sandbox.test("a path without the mount prefix lands in the sandbox too, and redirects are followed") { dir =>
    Wget.make[IO](web, List(dir)).run(args("https://site.test/moved", "page.html")).map { out =>
      assert(!Tool.isError(out), out)
      assert(Files.exists(dir.resolve("page.html")))
    }
  }

  sandbox.test("refuses paths outside the sandbox") { dir =>
    val wget = Wget.make[IO](web, List(dir))
    List("../escape.html", "sandbox/../../etc/passwd", "/etc/passwd", "sandbox").traverseRefusals(wget)
  }

  sandbox.test("an HTTP error is an error result") { dir =>
    Wget.make[IO](web, List(dir)).run(args("https://site.test/missing", "sandbox/x")).map { out =>
      assertEquals(out, Tool.error("GET https://site.test/missing failed with status 404"))
    }
  }

  extension (paths: List[String])
    private def traverseRefusals(wget: Tool[IO]): IO[Unit] =
      paths.foldLeft(IO.unit) { (acc, path) =>
        acc >> wget.run(args("https://site.test/page.html", path)).map { out =>
          assert(out.hcursor.get[String]("error").exists(_.startsWith("Refused")), s"$path -> $out")
        }
      }

  test("several mounts: addressed by name, unprefixed paths land in the first, names clash like files-mcp") {
    val tmp       = Files.createTempDirectory("yama-wget")
    val workspace = Files.createDirectories(tmp.resolve("work/yama"))
    val session   = Files.createDirectories(tmp.resolve("sessions/s1"))
    val clash     = Files.createDirectories(tmp.resolve("other/yama"))
    assertEquals(Wget.mountNames(List(workspace, session, clash)), List("yama", "s1", "yama_2"))
    val wget = Wget.make[IO](web, List(workspace, session))
    for
      inSession <- wget.run(args("https://site.test/page.html", "s1/downloads/page.html"))
      inFirst   <- wget.run(args("https://site.test/page.html", "page.html"))
      _         <- List("s1", "yama/../s1/x", "../x").traverseRefusals(wget)
    yield
      assertEquals(inSession.hcursor.get[String]("output_path"), Right("s1/downloads/page.html"))
      assert(Files.exists(session.resolve("downloads/page.html")))
      assertEquals(inFirst.hcursor.get[String]("output_path"), Right("yama/page.html"))
  }
