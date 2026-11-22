package yama.tool

import cats.effect.IO
import io.circe.Json
import munit.CatsEffectSuite
import yama.conversation.{CallId, Item, ToolName}

class ToolBoxSpec extends CatsEffectSuite:

  private val echo = Tool[IO]("echo", "", Json.obj())(IO.pure)
  private val box  = ToolBox[IO](List(echo, Tool[IO]("fail", "", Json.obj())(_ => IO.raiseError(RuntimeException("nope")))))

  private def run(name: String, args: String): IO[Json] =
    box.execute(Item.ToolCall(CallId("id"), ToolName(name), args)).map(_.output)

  test("runs the tool with parsed arguments")(run("echo", """{"a":1}""").assertEquals(Json.obj("a" -> Json.fromInt(1))))
  test("blank arguments mean an empty object")(run("echo", "").assertEquals(Json.obj()))
  test("unknown tool becomes an error result")(run("missing", "{}").assertEquals(Tool.error("Unknown tool: missing")))
  test("invalid JSON becomes an error result")(run("echo", "{oops").map(Tool.isError).assert)
  test("a failed F becomes an error result")(run("fail", "{}").assertEquals(Tool.error("nope")))

  test("later tools with the same name win") {
    val other = Tool[IO]("echo", "second", Json.obj())(_ => IO.pure(Json.True))
    val merged = box ++ List(other)
    assertEquals(merged.tools.map(_.name.value), List("fail", "echo"))
    assertEquals(merged.find(ToolName("echo")).map(_.description), Some("second"))
  }
