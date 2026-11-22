package yama.agent

import cats.Parallel
import cats.effect.{Concurrent, Ref}
import cats.syntax.all.*
import yama.agent.internal.Loop
import yama.chat.Chat
import yama.conversation.{Answer, Item}
import yama.log.{AgentLog, Stats}
import yama.tool.ToolBox

/** An agent: an LLM in a loop, with tools, where the model picks the next step. */
trait Agent[F[_]]:
  def query(text: String): F[Answer]
  def history: F[Vector[Item]]
  def reset: F[Unit]

object Agent:
  final case class Settings(name: String, instructions: String, maxRounds: Int)

  def make[F[_]: Concurrent: Parallel](
      settings: Settings,
      chat: Chat[F],
      tools: ToolBox[F],
      log: AgentLog[F],
      stats: Stats[F]
  ): F[Agent[F]] =
    Ref.of[F, Vector[Item]](Vector.empty).map { memory =>
      val loop = Loop(settings, chat, tools, log, stats)
      new Agent[F]:
        def query(text: String): F[Answer] =
          for
            _                 <- log.query(text)
            past              <- memory.get
            (convo, answer)   <- loop.run(past :+ Item.User(text), round = 1)
            _                 <- memory.set(convo)
            _                 <- log.answer(answer.text)
          yield answer
        def history: F[Vector[Item]] = memory.get
        def reset: F[Unit]           = memory.set(Vector.empty)
    }
