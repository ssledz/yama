package yama.cli

import cats.syntax.all.*
import com.monovore.decline.Opts
import yama.config.Overrides

import java.nio.file.Path

/** `configDir`/`config`: `None` falls back to `$YAMA_CONFIG_DIR`, then `~/.yama` (see `yama.config.Locations`). */
final case class CliArgs(configDir: Option[Path], config: Option[Path], overrides: Overrides)

/** `yama [--config-dir ~/.yama] [--config config.json] [--provider openrouter] [--model …] [--sandbox .] [--max-rounds 25] [--skills-path …]…`
  * Flags override the values from the file, so a live provider switch on stage is one flag.
  */
object Cli:
  val opts: Opts[CliArgs] =
    (
      Opts.option[Path]("config-dir", "yama's home: config.json, skills/, sessions/ (default: $YAMA_CONFIG_DIR, else ~/.yama).").orNone,
      Opts.option[Path]("config", "Path to config.json (default: <config-dir>/config.json).").orNone,
      Opts.option[String]("provider", "Provider profile from config.json (openai, openrouter, deepseek, ollama).").orNone,
      Opts.option[String]("model", "Model id, e.g. gpt-4.1 or openai/gpt-4.1.").orNone,
      Opts.option[String]("sandbox", "Workspace directory the agent may write to (default: the current directory).").orNone,
      Opts.option[Int]("max-rounds", "Round budget of the orchestrator loop.").orNone,
      Opts.options[Path]("skills-path", "Extra directory to discover skills in (repeatable).").orEmpty
    ).mapN { (configDir, config, provider, model, sandbox, maxRounds, skillsPaths) =>
      // made absolute here: relative paths in config.json are relative to the config file, flags to the shell
      CliArgs(configDir, config, Overrides(provider, model, sandbox, maxRounds, skillsPaths.map(_.toAbsolutePath.normalize.toString)))
    }
