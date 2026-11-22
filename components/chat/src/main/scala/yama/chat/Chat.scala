package yama.chat

import cats.effect.Temporal
import cats.effect.std.Random
import cats.syntax.all.*
import org.typelevel.log4cats.Logger
import retry.*
import retry.PolicyDecision.DelayAndRetry
import yama.conversation.Item

import scala.concurrent.duration.*

/** The only LLM API the agent sees. No URLs, keys, JSON or vendor names. */
trait Chat[F[_]]:
  def send(instructions: String, tools: List[ToolSpec], conversation: Vector[Item]): F[Reply]

object Chat:

  final case class RetryPolicyConfig(maxAttempts: Int, baseDelay: FiniteDuration, maxDelay: FiniteDuration)

  object RetryPolicyConfig:
    val default: RetryPolicyConfig = RetryPolicyConfig(5, 1.second, 60.seconds)

  /** Retries 429/5xx outside the loop: exponential backoff + full jitter, capped, honouring `Retry-After`.
    * The agent never learns that a retry happened.
    */
  def retrying[F[_]: Temporal: Random: Logger](config: RetryPolicyConfig)(chat: Chat[F]): Chat[F] =
    // Not RetryPolicies.capDelay: it is built with `meet`, which would keep retrying after limitRetries gives up.
    val backoff = RetryPolicies.limitRetries[F](config.maxAttempts - 1).join(RetryPolicies.fullJitter[F](config.baseDelay))
    // A Retry-After hint from the provider replaces the computed delay. Either way it is capped at maxDelay.
    val policy = RetryPolicy[F, Throwable] { (error, status) =>
      backoff.decideNextRetry(error, status).map {
        case DelayAndRetry(computed) =>
          val hinted = error match
            case ChatError.Retryable(_, _, Some(after)) => after
            case _                                      => computed
          DelayAndRetry(hinted.min(config.maxDelay))
        case giveUp => giveUp
      }
    }
    val handler = ResultHandler.retryOnSomeErrors[F, Reply](
      {
        case _: ChatError.Retryable => true
        case _                      => false
      },
      (error, details) =>
        details.nextStepIfUnsuccessful match
          case RetryDetails.NextStep.DelayAndRetry(delay) =>
            Logger[F].warn(
              s"${error.getMessage}: retry ${details.retriesSoFar + 1}/${config.maxAttempts - 1} in ${delay.toMillis}ms"
            )
          case RetryDetails.NextStep.GiveUp => Logger[F].error(s"${error.getMessage}: giving up")
    )
    (instructions, tools, conversation) => retryingOnErrors(chat.send(instructions, tools, conversation))(policy, handler)
