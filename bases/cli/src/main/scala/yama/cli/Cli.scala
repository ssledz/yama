package yama.cli

import cats.syntax.all.*
import com.monovore.decline.Opts
import yama.config.Overrides

import java.nio.file.{Path, Paths}

final case class CliArgs(config: Path, overrides: Overrides)

/** `yama [--config config.json] [--provider openrouter] [--model …] [--sandbox ./sandbox] [--max-rounds 25] [--skills-path …]…`
  * Flags override the values from the file, so a live provider switch on stage is one flag.
  */
object Cli:
  val opts: Opts[CliArgs] =
    (
      Opts.option[Path]("config", "Path to config.json.").withDefault(Paths.get("config.json")),
      Opts.option[String]("provider", "Provider profile from config.json (openai, openrouter, deepseek, ollama).").orNone,
      Opts.option[String]("model", "Model id, e.g. gpt-4.1 or openai/gpt-4.1.").orNone,
      Opts.option[String]("sandbox", "Sandbox directory the agent may write to.").orNone,
      Opts.option[Int]("max-rounds", "Round budget of the orchestrator loop.").orNone,
      Opts.options[String]("skills-path", "Extra directory to discover skills in (repeatable).").orEmpty
    ).mapN { (config, provider, model, sandbox, maxRounds, skillsPaths) =>
      CliArgs(config, Overrides(provider, model, sandbox, maxRounds, skillsPaths))
    }
