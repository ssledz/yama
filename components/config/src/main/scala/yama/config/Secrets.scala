package yama.config

import cats.effect.Async
import cats.syntax.all.*
import ciris.*

/** API keys, only ever from env vars, redacted when printed.
  * Only the selected provider's key is required; MCP server keys are optional (the server is skipped without one).
  */
final case class Secrets(chatApiKey: Secret[String], mcpApiKeys: Map[String, Secret[String]])

object Secrets:
  def load[F[_]: Async](config: Config): F[Secrets] =
    val provider = config.provider
    val chatKey  = env(provider.apiKeyEnv)
    val chat     = (if provider.apiKeyOptional then chatKey.default("unused") else chatKey).secret
    val keys: List[ConfigValue[Effect, (String, Option[Secret[String]])]] =
      config.mcpServers.toList.collect { case (name, McpServerSettings.Http(_, Some(keyEnv), _)) =>
        env(keyEnv).secret.option.map(name -> _)
      }
    // ConfigValue has no Applicative (no `pure`), so accumulate from a default instead of `sequence`.
    val mcp = keys.foldLeft(default(Map.empty[String, Secret[String]])) { (acc, key) =>
      (acc, key).parMapN { case (found, (name, value)) => value.fold(found)(found.updated(name, _)) }
    }
    (chat, mcp).parMapN(Secrets.apply).load[F]
