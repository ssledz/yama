package yama.chat.openai.internal

import cats.effect.Async
import cats.syntax.all.*
import ciris.Secret
import io.circe.parser.parse
import org.http4s.*
import org.http4s.circe.*
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.typelevel.ci.*
import yama.chat.{Chat, ChatError, Reply, ToolSpec}
import yama.chat.openai.ProviderConfig
import yama.conversation.Item

import java.io.IOException
import java.time.{Instant, ZonedDateTime}
import java.time.format.DateTimeFormatter
import java.util.concurrent.TimeoutException
import scala.concurrent.duration.*

/** One HTTP call: `POST {baseUrl}/responses`. */
private[openai] final class HttpChat[F[_]: Async](
    client: Client[F],
    provider: ProviderConfig,
    model: String,
    apiKey: Secret[String]
) extends Chat[F]:

  private val endpoint = Uri.unsafeFromString(provider.baseUrl.stripSuffix("/") + "/responses")

  private val headers =
    Headers(Authorization(Credentials.Token(AuthScheme.Bearer, apiKey.value))) ++
      Headers(provider.headers.toList.map((name, value) => Header.Raw(CIString(name), value)))

  def send(instructions: String, tools: List[ToolSpec], conversation: Vector[Item]): F[Reply] =
    val body    = WireFormat.request(model, instructions, tools, conversation, provider.strictTools)
    val request = Request[F](Method.POST, endpoint, headers = headers).withEntity(body)
    client
      .run(request)
      .use(response => response.bodyText.compile.string.flatMap(decode(response, _)))
      .adaptError {
        case e: (IOException | TimeoutException) => ChatError.Retryable(0, Option(e.getMessage).getOrElse(e.getClass.getSimpleName), None)
      }

  private def decode(response: Response[F], text: String): F[Reply] =
    if response.status.isSuccess then
      parse(text).leftMap(_.message).flatMap(WireFormat.reply).leftMap(ChatError.Fatal(response.status.code, _)).liftTo[F]
    else Async[F].raiseError(HttpChat.error(response.status.code, response.headers, text))

private[openai] object HttpChat:
  private val retryableStatus = Set(408, 409, 429)

  def error(status: Int, headers: Headers, body: String): ChatError =
    val detail = parse(body).toOption
      .flatMap(_.hcursor.downField("error").get[String]("message").toOption)
      .getOrElse(body.take(300))
    if retryableStatus(status) || status >= 500 then
      ChatError.Retryable(status, detail, retryAfter(headers).orElse(hintInBody(detail)))
    else ChatError.Fatal(status, detail)

  /** `Retry-After: 20` (seconds) or `Retry-After: <http-date>`. */
  def retryAfter(headers: Headers): Option[FiniteDuration] =
    headers.get(ci"Retry-After").map(_.head.value.trim).flatMap { value =>
      value.toDoubleOption.map(s => (s * 1000).toLong.millis).orElse {
        Either
          .catchNonFatal(ZonedDateTime.parse(value, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant)
          .toOption
          .map(at => (at.toEpochMilli - Instant.now.toEpochMilli).max(0L).millis)
      }
    }

  /** "Please try again in 15.734s" / "in 200ms": OpenAI says exactly how long. */
  def hintInBody(detail: String): Option[FiniteDuration] =
    """(?i)try again in ([\d.]+)(ms|s)""".r.findFirstMatchIn(detail).flatMap { m =>
      m.group(1).toDoubleOption.map(v => if m.group(2).equalsIgnoreCase("ms") then v.toLong.millis else (v * 1000).toLong.millis)
    }
