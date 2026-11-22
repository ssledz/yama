package yama.log

import cats.effect.{Concurrent, Ref}
import cats.syntax.all.*

/** Running token counters per agent, so the REPL can show what a session actually cost. */
trait Stats[F[_]]:
  def record(agent: String, inputTokens: Long, outputTokens: Long): F[Unit]
  def get: F[Map[String, Stats.Totals]]
  def reset: F[Unit]

object Stats:
  final case class Totals(requests: Int, inputTokens: Long, outputTokens: Long):
    def total: Long = inputTokens + outputTokens
    def +(other: Totals): Totals =
      Totals(requests + other.requests, inputTokens + other.inputTokens, outputTokens + other.outputTokens)

  object Totals:
    val zero: Totals = Totals(0, 0, 0)

  def make[F[_]: Concurrent]: F[Stats[F]] = Ref.of[F, Map[String, Totals]](Map.empty).map(fromRef)

  private def fromRef[F[_]](ref: Ref[F, Map[String, Totals]]): Stats[F] = new Stats[F]:
    def record(agent: String, in: Long, out: Long): F[Unit] =
      ref.update(m => m.updated(agent, m.getOrElse(agent, Totals.zero) + Totals(1, in, out)))
    def get: F[Map[String, Totals]] = ref.get
    def reset: F[Unit]              = ref.set(Map.empty)

  def render(stats: Map[String, Totals]): String =
    val all = stats.values.foldLeft(Totals.zero)(_ + _)
    val perAgent =
      if stats.size <= 1 then ""
      else stats.toList.sortBy(_._1).map((n, t) => s"\n    $n: ${t.requests} call(s), ${t.inputTokens} in / ${t.outputTokens} out").mkString
    s"📊 ${all.requests} model call(s), ${all.inputTokens} input tokens, ${all.outputTokens} output tokens, ${all.total} total$perAgent"
