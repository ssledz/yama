package yama.chat

import yama.conversation.Item

import scala.concurrent.duration.FiniteDuration

/** What the model sent back: assistant text and/or tool calls, plus what it cost. */
final case class Reply(output: Vector[Item], usage: Usage):
  def toolCalls: Vector[Item.ToolCall] = output.collect { case call: Item.ToolCall => call }
  def text: Option[String] =
    Option(output.collect { case Item.Assistant(text) => text }.mkString("\n").trim).filter(_.nonEmpty)

final case class Usage(inputTokens: Long, outputTokens: Long, cachedTokens: Option[Long] = None)

object Usage:
  val zero: Usage = Usage(0, 0)

/** Errors a provider can raise. Only `Retryable` is retried, and only by [[Chat.retrying]]. */
sealed abstract class ChatError(message: String) extends Exception(message)

object ChatError:
  /** `status` 0 = no HTTP response at all (network error, timeout). */
  final case class Retryable(status: Int, detail: String, retryAfter: Option[FiniteDuration])
      extends ChatError(if status == 0 then s"model API unreachable: $detail" else s"model API returned $status: $detail")
  final case class Fatal(status: Int, detail: String) extends ChatError(s"model API returned $status: $detail")
