package yama.chat.openai

import cats.effect.Async
import ciris.Secret
import org.http4s.client.Client
import yama.chat.Chat
import yama.chat.openai.internal.HttpChat

/** The only brick that knows the vendor: `Chat[F]` over the OpenAI Responses API wire format. */
object OpenAiCompat:
  def make[F[_]: Async](client: Client[F], provider: ProviderConfig, model: String, apiKey: Secret[String]): Chat[F] =
    HttpChat(client, provider, model, apiKey)
