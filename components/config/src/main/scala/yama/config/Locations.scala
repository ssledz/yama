package yama.config

import cats.effect.Sync

import java.nio.file.{Path, Paths}

/** Where yama keeps its own files. The config dir is `--config-dir`, else `$YAMA_CONFIG_DIR`, else `~/.yama`:
  * {{{
  *   <configDir>/config.json                          unless --config points elsewhere
  *   <configDir>/skills/                              user-level skills
  *   <configDir>/sessions/<encoded cwd>/<session id>/ per-run scratch space the agent may write to
  * }}}
  */
/** `bootstrap`: the config file is the default one, so a missing file may be created from the bundled defaults.
  * An explicit `--config` that does not exist stays an error: it is more likely a typo than a first run.
  */
final case class Locations(configDir: Path, configFile: Path, bootstrap: Boolean = false):
  def skillsDir: Path   = configDir.resolve("skills")
  def sessionsDir: Path = configDir.resolve("sessions")

  /** `~/.yama/sessions/-Users-me-git-yama/<id>`: one folder per working directory, one per run below it. */
  def sessionDir(workspace: Path, sessionId: String): Path =
    sessionsDir.resolve(Locations.encode(workspace)).resolve(sessionId)

object Locations:
  val EnvVar = "YAMA_CONFIG_DIR"

  /** A flag beats the env var, which beats `~/.yama`. `--config` alone moves only the file, not the dir. */
  def resolve(configDirFlag: Option[Path], configFileFlag: Option[Path], envConfigDir: Option[String], home: Path): Locations =
    val dir = configDirFlag
      .orElse(envConfigDir.map(_.trim).filter(_.nonEmpty).map(Paths.get(_)))
      .getOrElse(home.resolve(".yama"))
      .toAbsolutePath
      .normalize
    configFileFlag.fold(Locations(dir, dir.resolve("config.json"), bootstrap = true))(f => Locations(dir, f.toAbsolutePath.normalize))

  def load[F[_]: Sync](configDirFlag: Option[Path], configFileFlag: Option[Path]): F[Locations] =
    Sync[F].delay(resolve(configDirFlag, configFileFlag, sys.env.get(EnvVar), Paths.get(sys.props("user.home"))))

  /** A path as one folder name, the way Claude Code does it: `/Users/me/git/yama` → `-Users-me-git-yama`. */
  def encode(path: Path): String = path.toAbsolutePath.normalize.toString.replaceAll("[^A-Za-z0-9._-]", "-")
