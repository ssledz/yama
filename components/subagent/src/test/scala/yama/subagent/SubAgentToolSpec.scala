package yama.subagent

import cats.effect.IO
import io.circe.Json
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.typelevel.log4cats.Logger
import org.typelevel.log4cats.noop.NoOpLogger
import yama.agent.Agent
import yama.chat.ScriptedChat
import yama.chat.ScriptedChat.*
import yama.conversation.{Item, ToolName}
import yama.log.{AgentLog, Stats}
import yama.tool.{Tool, ToolBox}

class SubAgentToolSpec extends CatsEffectSuite:
  given Logger[IO] = NoOpLogger[IO]

  private val spec   = SubAgentSpec("research_agent", "Researches the web.", "You research.", List("search"), None, maxRounds = 2)
  private val search = Tool[IO]("search", "Web search.", Json.obj())(_ => IO.pure(Json.obj("results" -> List("a", "b").asJson)))

  private def setup(worker: ScriptedChat, orchestrator: ScriptedChat): IO[(Agent[IO], Stats[IO])] =
    for
      stats       <- Stats.make[IO]
      log          = AgentLog.make[IO]
      researchTool = SubAgent.asTool[IO](spec, SubAgent.worker(spec, worker, ToolBox(List(search)), log, stats))
      main        <- Agent.make[IO](Agent.Settings("yama", "orchestrate", 5), orchestrator, ToolBox(List(researchTool)), log, stats)
    yield (main, stats)

  test("the worker's answer comes back to the orchestrator as a tool result") {
    for
      worker       <- ScriptedChat(calls(call("w1", "search", "{}")), answer("Art of Scala is a conference."))
      orchestrator <- ScriptedChat(calls(call("o1", "research_agent", """{"input":"what is AoS?"}""")), answer("Done."))
      (main, stats) <- setup(worker, orchestrator)
      answer       <- main.query("research AoS")
      requests     <- orchestrator.requests
      perAgent     <- stats.get
    yield
      assertEquals(answer.text, "Done.")
      val result = requests(1).conversation.collect { case Item.ToolResult(_, out) => out }
      assertEquals(result, Vector(Json.obj("answer" -> "Art of Scala is a conference.".asJson)))
      assertEquals(perAgent.keySet, Set("yama", "research_agent"))
  }

  test("every call gets a fresh conversation") {
    for
      worker <- ScriptedChat(answer("first"), answer("second"))
      orchestrator <- ScriptedChat(
        calls(call("o1", "research_agent", """{"input":"q1"}""")),
        calls(call("o2", "research_agent", """{"input":"q2"}""")),
        answer("ok")
      )
      (main, _) <- setup(worker, orchestrator)
      _         <- main.query("go")
      seen      <- worker.requests
    yield assertEquals(seen.map(_.conversation), Vector(Vector(Item.User("q1")), Vector(Item.User("q2"))))
  }

  test("a worker that hits its round limit returns an error result") {
    for
      worker       <- ScriptedChat(List.fill(5)(calls(call("w", "search", "{}")))*)
      orchestrator <- ScriptedChat(calls(call("o1", "research_agent", """{"input":"q"}""")), answer("gave up"))
      (main, _)    <- setup(worker, orchestrator)
      _            <- main.query("go")
      requests     <- orchestrator.requests
      workerCalls  <- worker.requests
    yield
      assertEquals(workerCalls.size, spec.maxRounds)
      val result = requests(1).conversation.collect { case Item.ToolResult(_, out) => out }.head
      assert(Tool.isError(result), result)
  }

  test("depth = 1: the worker sees only its own tools, never a subagent tool") {
    for
      worker       <- ScriptedChat(answer("fine"))
      orchestrator <- ScriptedChat(calls(call("o1", "research_agent", """{"input":"q"}""")), answer("ok"))
      (main, _)    <- setup(worker, orchestrator)
      _            <- main.query("go")
      workerTools  <- worker.requests.map(_.head.tools.map(_.name))
      mainTools    <- orchestrator.requests.map(_.head.tools.map(_.name))
    yield
      assertEquals(workerTools, List(ToolName("search")))
      assertEquals(mainTools, List(ToolName("research_agent")))
  }
