package yama.agent

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import yama.chat.ScriptedChat
import yama.chat.ScriptedChat.*
import yama.conversation.{Answer, CallId, Item}
import yama.log.{AgentLog, Stats}
import yama.tool.{Tool, ToolBox}

class AgentSpec extends CatsEffectSuite:
  given Logger[IO] = NoOpLogger[IO]

  private val echo = Tool[IO]("echo", "Echoes its input.", Json.obj())(args => IO.pure(Json.obj("echo" -> args)))
  private val boom = Tool[IO]("boom", "Always fails.", Json.obj())(_ => IO.raiseError(RuntimeException("kaboom")))

  private def agent(chat: ScriptedChat, maxRounds: Int = 5): IO[Agent[IO]] =
    Stats.make[IO].flatMap { stats =>
      Agent.make[IO](Agent.Settings("test", "be helpful", maxRounds), chat, ToolBox(List(echo, boom)), AgentLog.make[IO], stats)
    }

  test("no tool calls means the answer, in one round") {
    for
      chat   <- ScriptedChat(answer("hi"))
      answer <- agent(chat).flatMap(_.query("hello"))
    yield assertEquals(answer, Answer.Final("hi", 1))
  }

  test("tool calls run, their results go back into the conversation, then the model answers") {
    for
      chat     <- ScriptedChat(calls(call("c1", "echo", """{"x":1}""")), answer("done"))
      a        <- agent(chat)
      answer   <- a.query("go")
      requests <- chat.requests
      history  <- a.history
    yield
      assertEquals(answer, Answer.Final("done", 2))
      assertEquals(requests.size, 2)
      // the second round sees the call and its result: the conversation is the state
      assertEquals(
        requests(1).conversation,
        Vector(Item.User("go"), call("c1", "echo", """{"x":1}"""), Item.ToolResult(CallId("c1"), Json.obj("echo" -> Json.obj("x" -> 1.asJson))))
      )
      assertEquals(history.last, Item.Assistant("done"))
  }

  test("tool calls of one round run in parallel and keep their order") {
    for
      chat     <- ScriptedChat(calls(call("a", "echo", """{"n":1}"""), call("b", "echo", """{"n":2}""")), answer("ok"))
      _        <- agent(chat).flatMap(_.query("go"))
      requests <- chat.requests
    yield
      val results = requests(1).conversation.collect { case Item.ToolResult(id, _) => id.value }
      assertEquals(results, Vector("a", "b"))
  }

  test("failures are data: unknown tool, bad JSON and a failing tool all become error results") {
    for
      chat <- ScriptedChat(
        calls(call("1", "nope", "{}"), call("2", "echo", "{not json"), call("3", "boom", "{}")),
        answer("recovered")
      )
      answer   <- agent(chat).flatMap(_.query("go"))
      requests <- chat.requests
    yield
      assertEquals(answer.text, "recovered")
      val errors = requests(1).conversation.collect { case Item.ToolResult(_, out) => out.hcursor.get[String]("error").toOption }
      assertEquals(errors.size, 3)
      assert(errors.forall(_.isDefined), errors)
      assertEquals(errors(2), Some("kaboom"))
  }

  test("the loop gives up after maxRounds") {
    val forever = List.fill(10)(calls(call("x", "echo", "{}")))
    for
      chat     <- ScriptedChat(forever*)
      answer   <- agent(chat, maxRounds = 3).flatMap(_.query("loop"))
      requests <- chat.requests
    yield
      assertEquals(answer, Answer.GaveUp(3))
      assertEquals(requests.size, 3)
  }

  test("memory grows across queries and reset forgets it") {
    for
      chat   <- ScriptedChat(answer("one"), answer("two"))
      a      <- agent(chat)
      _      <- a.query("first")
      _      <- a.query("second")
      second <- chat.requests.map(_(1).conversation)
      _      <- a.reset
      empty  <- a.history
    yield
      assertEquals(second, Vector(Item.User("first"), Item.Assistant("one"), Item.User("second")))
      assertEquals(empty, Vector.empty)
  }

  test("the model sees every tool's spec") {
    for
      chat     <- ScriptedChat(answer("hi"))
      _        <- agent(chat).flatMap(_.query("hello"))
      requests <- chat.requests
    yield assertEquals(requests.head.tools.map(_.name.value), List("echo", "boom"))
  }
