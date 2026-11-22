package yama.agent.internal

import cats.Parallel
import cats.effect.Concurrent
import cats.syntax.all.*
import yama.agent.Agent
import yama.chat.{Chat, ToolSpec}
import yama.conversation.{Answer, Item}
import yama.log.{AgentLog, Stats}
import yama.tool.{Tool, ToolBox}

/** The loop. This is all the AI there is. */
private[agent] final class Loop[F[_]: Concurrent: Parallel](
    settings: Agent.Settings,
    chat: Chat[F],
    tools: ToolBox[F],
    log: AgentLog[F],
    stats: Stats[F]
):
  import settings.{instructions, maxRounds}

  private val specs = tools.tools.map(t => ToolSpec(t.name, t.description, t.schema, t.strict))

  def run(conversation: Vector[Item], round: Int): F[(Vector[Item], Answer)] =
    if round > maxRounds then (conversation, Answer.GaveUp(maxRounds)).pure[F]
    else
      for
        _      <- log.round(round, maxRounds)
        _      <- log.request(conversation.size, specs.size)
        reply  <- chat.send(instructions, specs, conversation)
        _      <- log.usage(reply.usage.inputTokens, reply.usage.outputTokens)
        _      <- stats.record(settings.name, reply.usage.inputTokens, reply.usage.outputTokens)
        next    = conversation ++ reply.output
        result <-
          if reply.toolCalls.isEmpty then (next, Answer.Final(reply.text.getOrElse(""), round)).pure[F]
          else reply.toolCalls.parTraverse(execute).flatMap(results => run(next ++ results, round + 1))
      yield result

  private def execute(call: Item.ToolCall): F[Item.ToolResult] =
    val origin = tools.find(call.name).fold("unknown")(_.origin)
    for
      _      <- log.toolCall(call.name.value, call.args, origin)
      result <- tools.execute(call)
      _      <- log.toolResult(!Tool.isError(result.output), result.output.noSpaces)
    yield result
