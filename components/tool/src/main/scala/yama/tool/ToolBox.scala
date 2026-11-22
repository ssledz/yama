package yama.tool

import cats.MonadThrow
import cats.syntax.all.*
import io.circe.parser.parse
import yama.conversation.{Item, ToolName}

/** The registry of tools an agent may call, and the one place they are executed.
  * Unknown tool, bad JSON arguments or a failed `F`: all become a `ToolResult` with `{"error": ...}`.
  * A failure never escapes the loop.
  */
final class ToolBox[F[_]: MonadThrow] private (byName: Map[ToolName, Tool[F]], order: List[Tool[F]]):

  def tools: List[Tool[F]] = order

  def find(name: ToolName): Option[Tool[F]] = byName.get(name)

  def execute(call: Item.ToolCall): F[Item.ToolResult] =
    val output = byName.get(call.name) match
      case None => Tool.error(s"Unknown tool: ${call.name.value}").pure[F]
      case Some(tool) =>
        parse(if call.args.isBlank then "{}" else call.args) match
          case Left(failure) => Tool.error(s"Arguments are not valid JSON: ${failure.message}").pure[F]
          case Right(args)   => tool.run(args).handleError(e => Tool.error(Option(e.getMessage).getOrElse(e.toString)))
    output.map(Item.ToolResult(call.id, _))

  def ++(other: List[Tool[F]]): ToolBox[F] = ToolBox(order ++ other)

object ToolBox:
  /** Later tools with the same name replace earlier ones. */
  def apply[F[_]: MonadThrow](tools: List[Tool[F]]): ToolBox[F] =
    val byName = tools.map(t => t.name -> t).toMap
    new ToolBox(byName, tools.filter(t => byName(t.name) eq t))
