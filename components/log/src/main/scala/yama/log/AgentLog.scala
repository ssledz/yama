package yama.log

import org.typelevel.log4cats.Logger

/** Domain-level log events of the loop: queries, rounds, tool calls, tokens.
  * Deliberately verbose: the point of yama is to make the loop visible.
  */
trait AgentLog[F[_]]:
  def query(text: String): F[Unit]
  def round(current: Int, max: Int): F[Unit]
  def request(items: Int, tools: Int): F[Unit]
  def usage(inputTokens: Long, outputTokens: Long): F[Unit]
  def toolCall(name: String, args: String, origin: String): F[Unit]
  def toolResult(ok: Boolean, output: String): F[Unit]
  def answer(text: String): F[Unit]
  def info(message: String): F[Unit]
  def warn(message: String): F[Unit]

  /** The same log, indented and prefixed: a worker's loop shown inside one round of the outer loop. */
  def nested(context: String): AgentLog[F]

object AgentLog:
  def make[F[_]: Logger]: AgentLog[F] = LoggerAgentLog[F](prefix = "")

  private final class LoggerAgentLog[F[_]: Logger](prefix: String) extends AgentLog[F]:
    import Ansi.*
    private val log                       = Logger[F]
    private def line(text: String): String = prefix + text

    def query(text: String): F[Unit]           = log.info(line(s"${bold(bgBlue(" QUERY "))} $text"))
    def round(current: Int, max: Int): F[Unit] = log.info(line(s"${magenta("◆")} round ${bold(s"$current/$max")}"))
    def request(items: Int, tools: Int): F[Unit] =
      log.info(line(dim(s"  → POST /responses  input=$items items  tools=$tools")))
    def usage(in: Long, out: Long): F[Unit] = log.info(line(dim(s"  ← tokens: $in in / $out out")))
    def toolCall(name: String, args: String, origin: String): F[Unit] =
      val label = if origin == "native" then yellow("⚡ native") else green(s"🔌 $origin")
      log.info(line(s"$label ${bold(name)} ${dim(truncate(args, 160))}"))
    def toolResult(ok: Boolean, output: String): F[Unit] =
      log.info(line(dim(s"  ${if ok then green("✓") else red("✗")} ${truncate(output, 200)}")))
    def answer(text: String): F[Unit] = log.info(line(s"${bold(bgGreen(" ANSWER "))} ${truncate(text, 200)}"))
    def info(message: String): F[Unit] = log.info(line(message))
    def warn(message: String): F[Unit] = log.warn(line(message))

    def nested(context: String): AgentLog[F] = LoggerAgentLog[F](s"$prefix${dim("│")} ${cyan(s"[$context]")} ")

  private def truncate(text: String, max: Int): String =
    val oneLine = text.replace('\n', ' ')
    if oneLine.length > max then oneLine.take(max) + "…" else oneLine
