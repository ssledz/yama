package yama.config

import cats.effect.Async
import cats.syntax.all.*
import ciris.*
import ciris.circe.circeConfigDecoder
import io.circe.{Decoder, DecodingFailure}
import io.circe.derivation.{Configuration, ConfiguredDecoder}

import java.nio.file.Path

private given Configuration = Configuration.default.withDefaults

/** Everything except secrets. Read from `config.json`; secrets come only from env vars (see [[Secrets]]). */
final case class Config(
    chat: ChatSettings,
    providers: Map[String, ProviderSettings],
    agent: AgentSettings,
    sandbox: SandboxSettings,
    mcpServers: Map[String, McpServerSettings] = Map.empty,
    skills: SkillsSettings = SkillsSettings(),
    subagents: Map[String, SubAgentSettings] = Map.empty
) derives ConfiguredDecoder:
  def provider: ProviderSettings = providers(chat.provider)

final case class ChatSettings(provider: String, model: String, retry: RetrySettings = RetrySettings())
    derives ConfiguredDecoder

final case class RetrySettings(maxAttempts: Int = 5, baseDelayMs: Long = 1000, maxDelayMs: Long = 60000)
    derives ConfiguredDecoder

/** A provider is just data: where to POST, which env var holds the key, extra headers, capability flags. */
final case class ProviderSettings(
    baseUrl: String,
    apiKeyEnv: String,
    apiKeyOptional: Boolean = false,
    headers: Map[String, String] = Map.empty,
    strictTools: Boolean = false
) derives ConfiguredDecoder

/** `mcpServers`: which servers' tools the orchestrator gets (default: all). The rest are for workers only. */
final case class AgentSettings(instructions: String, maxRounds: Int = 25, mcpServers: Option[List[String]] = None)
    derives ConfiguredDecoder

final case class SandboxSettings(dir: String = "./sandbox") derives ConfiguredDecoder

final case class SkillsSettings(paths: List[String] = Nil, trustProject: Boolean = true) derives ConfiguredDecoder

final case class SubAgentSettings(
    description: String,
    instructions: String,
    tools: List[String],
    model: Option[String] = None,
    maxRounds: Int = 8
) derives ConfiguredDecoder

/** `type` selects the transport; no `type` means a local stdio process. */
enum McpServerSettings:
  case Local(command: String, args: List[String], env: Map[String, String], cwd: Option[String])
  case Http(url: String, apiKeyEnv: Option[String], headers: Map[String, String])

object McpServerSettings:
  given Decoder[McpServerSettings] = Decoder.instance { c =>
    c.getOrElse[String]("type")("stdio").flatMap {
      case "stdio" =>
        (
          c.get[String]("command"),
          c.getOrElse[List[String]]("args")(Nil),
          c.getOrElse[Map[String, String]]("env")(Map.empty),
          c.get[Option[String]]("cwd")
        ).mapN(Local.apply)
      case "http" =>
        (
          c.get[String]("url"),
          c.get[Option[String]]("apiKeyEnv"),
          c.getOrElse[Map[String, String]]("headers")(Map.empty)
        ).mapN(Http.apply)
      case other => Left(DecodingFailure(s"unknown MCP server type '$other' (expected stdio or http)", c.history))
    }
  }

/** CLI flags. A flag beats an env var (`YAMA_PROVIDER`, `YAMA_MODEL`), which beats `config.json`. */
final case class Overrides(
    provider: Option[String] = None,
    model: Option[String] = None,
    sandbox: Option[String] = None,
    maxRounds: Option[Int] = None,
    skillsPaths: List[String] = Nil
)

object Config:
  def load[F[_]: Async](path: Path, overrides: Overrides): F[Config] =
    (
      file(path).as[Config](using circeConfigDecoder("Config")),
      env("YAMA_PROVIDER").option,
      env("YAMA_MODEL").option
    ).parMapN { (fromFile, envProvider, envModel) =>
      val chat = fromFile.chat.copy(
        provider = overrides.provider.orElse(envProvider).getOrElse(fromFile.chat.provider),
        model = overrides.model.orElse(envModel).getOrElse(fromFile.chat.model)
      )
      fromFile.copy(
        chat = chat,
        agent = fromFile.agent.copy(maxRounds = overrides.maxRounds.getOrElse(fromFile.agent.maxRounds)),
        sandbox = overrides.sandbox.fold(fromFile.sandbox)(SandboxSettings(_)),
        skills = fromFile.skills.copy(paths = fromFile.skills.paths ++ overrides.skillsPaths)
      )
    }.load[F]
      .flatTap { config =>
        Async[F]
          .raiseError(
            IllegalArgumentException(
              s"unknown provider '${config.chat.provider}', configured: ${config.providers.keys.toList.sorted.mkString(", ")}"
            )
          )
          .whenA(!config.providers.contains(config.chat.provider))
      }
