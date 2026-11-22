package yama.subagent

import cats.{MonadThrow, Parallel}
import cats.effect.Concurrent
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import io.circe.syntax.*
import yama.agent.Agent
import yama.chat.Chat
import yama.conversation.Answer
import yama.log.{AgentLog, Stats}
import yama.tool.{Tool, ToolBox, ToolSchema}

/** A specialist: its own instructions, its own tool subset, optionally its own model, its own round budget. */
final case class SubAgentSpec(
    name: String,
    description: String,
    instructions: String,
    tools: List[String],
    model: Option[String],
    maxRounds: Int
)

final case class SubAgentInput(input: String) derives ToolSchema, Decoder

/** Orchestrator–worker: a subagent is a tool whose implementation is another agent. */
object SubAgent:

  def asTool[F[_]: MonadThrow](spec: SubAgentSpec, makeAgent: F[Agent[F]]): Tool[F] =
    Tool[F](
      name = spec.name,
      description = spec.description, // the orchestrator reads this to decide when to delegate
      schema = ToolSchema[SubAgentInput].describe("input" -> "The task for the specialist, in plain language."),
      origin = "agent",
      strict = true
    ) { args =>
      for
        in     <- args.as[SubAgentInput].liftTo[F]
        agent  <- makeAgent           // fresh agent = fresh conversation per call
        answer <- agent.query(in.input) // the worker runs its own loop
      yield answer match
        case Answer.Final(text, _)   => Json.obj("answer" -> text.asJson)
        case gaveUp: Answer.GaveUp   => Tool.error(s"${spec.name}: ${gaveUp.text}")
    }

  /** A worker is built by the same `Agent.make`; it logs nested under its name. Workers get no subagent tools (depth = 1). */
  def worker[F[_]: Concurrent: Parallel](
      spec: SubAgentSpec,
      chat: Chat[F],
      tools: ToolBox[F],
      log: AgentLog[F],
      stats: Stats[F]
  ): F[Agent[F]] =
    Agent.make(Agent.Settings(spec.name, spec.instructions, spec.maxRounds), chat, tools, log.nested(spec.name), stats)
