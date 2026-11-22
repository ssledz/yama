package yama.chat

import cats.effect.{IO, Ref}
import cats.syntax.all.*
import yama.conversation.{CallId, Item, ToolName}

/** A test double for the `chat` interface: replies come from a script, every request is recorded.
  * It makes the loop fully deterministic.
  */
final class ScriptedChat private (script: Ref[IO, List[Reply]], recorded: Ref[IO, Vector[ScriptedChat.Request]])
    extends Chat[IO]:

  def send(instructions: String, tools: List[ToolSpec], conversation: Vector[Item]): IO[Reply] =
    recorded.update(_ :+ ScriptedChat.Request(instructions, tools, conversation)) >>
      script.modify {
        case next :: rest => (rest, IO.pure(next))
        case Nil          => (Nil, IO.raiseError(IllegalStateException("ScriptedChat: script exhausted")))
      }.flatten

  def requests: IO[Vector[ScriptedChat.Request]] = recorded.get

object ScriptedChat:
  final case class Request(instructions: String, tools: List[ToolSpec], conversation: Vector[Item])

  def apply(replies: Reply*): IO[ScriptedChat] =
    (Ref.of[IO, List[Reply]](replies.toList), Ref.of[IO, Vector[Request]](Vector.empty)).mapN(new ScriptedChat(_, _))

  // Script helpers
  def answer(text: String): Reply = Reply(Vector(Item.Assistant(text)), Usage(10, 5))

  def call(id: String, tool: String, args: String): Item.ToolCall = Item.ToolCall(CallId(id), ToolName(tool), args)

  def calls(toolCalls: Item.ToolCall*): Reply = Reply(toolCalls.toVector, Usage(10, 5))

