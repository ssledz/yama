package yama.cli

import cats.Parallel
import cats.effect.{Async, ExitCode, Resource}
import cats.effect.std.{Console, Random}
import cats.syntax.all.*
import fs2.io.file.Files
import fs2.io.net.Network
import fs2.io.process.Processes
import org.http4s.ember.client.EmberClientBuilder
import org.typelevel.log4cats.{Logger, LoggerFactory}
import yama.agent.Agent
import yama.chat.Chat
import yama.chat.openai.{OpenAiCompat, ProviderConfig}
import yama.config.{Bootstrap, Config, Locations, McpServerSettings, Overrides, Secrets}
import yama.log.{AgentLog, Stats}
import yama.mcp.client.{McpClient, McpServer}
import yama.mcp.tools.McpTools
import yama.skills.Skills
import yama.subagent.{SubAgent, SubAgentSpec}
import yama.tool.{Tool, ToolBox}
import yama.wget.Wget

import java.nio.file.{Path, Paths}
import scala.concurrent.duration.*

/** Polylith composition happens here, in the base: config → chat → MCP servers → skills → subagents → orchestrator. */
object Wiring:

  def run[F[_]: Async: Parallel: Console: LoggerFactory: Processes: Files: Network](args: CliArgs): F[ExitCode] =
    LoggerFactory[F].fromName("yama").flatMap { logger =>
      given Logger[F] = logger
      app(args).use((repl, banner) => Console[F].println(banner) >> repl.run).as(ExitCode.Success)
    }.handleErrorWith { error =>
      Console[F].errorln(s"yama: ${Option(error.getMessage).getOrElse(error.toString)}").as(ExitCode.Error)
    }

  private def app[F[_]: Async: Parallel: Console: Logger: LoggerFactory: Processes: Files: Network](args: CliArgs): Resource[F, (Repl[F], String)] =
    for
      where   <- Resource.eval(Locations.load[F](args.configDir, args.config))
      config  <- Resource.eval(loadConfig(where, args.overrides))
      secrets <- Resource.eval(Secrets.load[F](config))
      project  = Paths.get("").toAbsolutePath
      home     = Paths.get(sys.props("user.home"))
      // relative paths inside config.json (MCP cwd, skills.paths) are relative to the file, so ~/.yama works from anywhere
      base     = where.configFile.getParent
      // two writable roots: the workspace (default: where yama runs) and this run's own session folder. The session
      // folder is not created here: fs_write, fs_manage and wget create it on the first write, so a run that writes
      // nothing leaves no trace in ~/.yama/sessions.
      sandbox  = project.resolve(config.sandbox.dir.getOrElse(".")).normalize
      session <- Resource.eval(newSessionId[F].map(where.sessionDir(project, _)))
      _       <- Resource.eval(Files[F].createDirectories(fs2.io.file.Path.fromNioPath(sandbox)))
      mounts   = Mounts(sandbox, session)
      given Random[F] <- Resource.eval(Random.scalaUtilRandom[F])
      client  <- EmberClientBuilder.default[F].withTimeout(5.minutes).withIdleConnectionTime(10.minutes).build
      stats   <- Resource.eval(Stats.make[F])
      log      = AgentLog.make[F]
      retry    = Chat.RetryPolicyConfig(config.chat.retry.maxAttempts, config.chat.retry.baseDelayMs.millis, config.chat.retry.maxDelayMs.millis)
      provider = ProviderConfig(config.chat.provider, config.provider.baseUrl, config.provider.headers, config.provider.strictTools)
      chatFor  = (model: String) => Chat.retrying(retry)(OpenAiCompat.make(client, provider, model, secrets.chatApiKey))
      chat     = chatFor(config.chat.model)
      // MCP: local servers are spawned (FS_ROOTS injected, so server and wget agree on the mounts), remote ones connected.
      servers <- Resource.eval(mcpServers(config, secrets, base, mounts))
      clients <- servers.traverse(server => McpClient.connect[F](server))
      mcp     <- Resource.eval(clients.traverse(c => McpTools.from(c).map(c.server -> _)))
      _       <- Resource.eval(mcp.traverse_((name, tools) => log.info(s"✓ MCP server '$name': ${tools.map(_.name.value).mkString(", ")}")))
      wget     = Wget.make[F](client, mounts.roots)
      builtin <- Skills.builtinRoot[F]
      extra    = config.skills.paths.map(base.resolve(_).normalize)
      skills  <- Resource.eval(Skills.discover[F](Skills.defaultRoots(project, where.skillsDir, home, extra, config.skills.trustProject, builtin)))
      _       <- Resource.eval(skills.diagnostics.traverse_(d => log.warn(s"skill ${d.location}: ${d.message}${if d.skipped then " (skipped)" else ""}")))
      // Workers pick their tools by name from the native + MCP pool. They never get subagent tools: depth = 1.
      pool     = (wget :: mcp.flatMap(_._2)).map(t => t.name.value -> t).toMap
      skippedServers = config.mcpServers.keySet -- servers.map(_.name).toSet
      specs   <- Resource.eval(subAgentSpecs(config, pool.keySet, skippedServers, log))
      workers  = specs.map { spec =>
        val workerChat = spec.model.fold(chat)(chatFor)
        SubAgent.asTool[F](spec, SubAgent.worker(spec, workerChat, ToolBox(spec.tools.map(pool)), log, stats))
      }
      visible  = config.agent.mcpServers.fold(mcp)(names => mcp.filter((server, _) => names.contains(server)))
      tools    = ToolBox[F](wget :: visible.flatMap(_._2) ++ skills.tools ++ workers)
      prompt   = instructions(config.agent.instructions, mounts) + Option(skills.catalogPrompt).filter(_.nonEmpty).fold("")("\n\n" + _)
      agent   <- Resource.eval(Agent.make[F](Agent.Settings("yama", prompt, config.agent.maxRounds), chat, tools, log, stats))
      _       <- Resource.onFinalize(stats.get.flatMap(s => log.info(Stats.render(s))))
    yield (
      Repl[F](agent, tools.tools, prompt, skills, stats),
      banner(config, where, mounts, skills.catalog.map(_.name), specs.map(_.name))
    )

  /** The workspace and the session folder, with the names the model addresses them by (same rule in wget and files-mcp). */
  private final case class Mounts(workspace: Path, session: Path):
    def roots: List[Path]                    = List(workspace, session)
    val List(workspaceName, sessionName) = Wget.mountNames(roots): @unchecked

  /** First run at the default location: the bundled defaults are written there. An explicit `--config` that does
    * not exist is an error with a hint, not just ciris' "file not found".
    */
  private def loadConfig[F[_]: Async: Logger](where: Locations, overrides: Overrides): F[Config] =
    val created =
      if where.bootstrap then
        Bootstrap.write[F](where.configFile, Bootstrap.npxOnPath(sys.env.get("PATH"))).flatMap {
          case Bootstrap.Outcome.Written(true)  =>
            Logger[F].info(s"first run: created ${where.configFile}; the files MCP server runs from npm via npx")
          case Bootstrap.Outcome.Written(false) =>
            Logger[F].warn(s"first run: created ${where.configFile}; npx not found, so set mcpServers.files.cwd to a files-mcp checkout")
          case Bootstrap.Outcome.Existing => Async[F].unit
        }
      else Async[F].unit
    created *> Async[F].blocking(java.nio.file.Files.isRegularFile(where.configFile)).flatMap { exists =>
      if exists then Config.load[F](where.configFile, overrides)
      else
        Async[F].raiseError(IllegalStateException(
          s"no config at ${where.configFile}: pass an existing file to --config, or leave it out to use " +
            s"<config-dir>/config.json (created on first run; --config-dir or ${Locations.EnvVar} move it)"
        ))
    }

  /** Sortable and unique enough: `20261124-093015-3fa9c2`. */
  private def newSessionId[F[_]: Async]: F[String] =
    Async[F].delay {
      val time = java.time.LocalDateTime.now.format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
      f"$time-${scala.util.Random.nextInt(0x1000000)}%06x"
    }

  private def mcpServers[F[_]: Async: Logger](config: Config, secrets: Secrets, base: Path, mounts: Mounts): F[List[McpServer]] =
    config.mcpServers.toList.sortBy(_._1).flatTraverse {
      case (name, McpServerSettings.Local(command, args, env, cwd)) =>
        val dir = cwd.map(base.resolve(_).normalize)
        val missingDir  = dir.filterNot(_.toFile.isDirectory)
        val missingDeps = dir.filter(d => command == "npx" && !d.resolve("node_modules").toFile.isDirectory)
        (missingDir, missingDeps) match
          case (Some(d), _) =>
            Async[F].raiseError(IllegalStateException(
              s"MCP server '$name': its cwd $d does not exist. Set mcpServers.$name.cwd in ${base.resolve("config.json")} " +
                "to an absolute path (relative paths are resolved against the config file's directory)"
            ))
          case (None, Some(d)) =>
            Async[F].raiseError(IllegalStateException(s"MCP server '$name' is not installed: run `npm install --prefix $d`"))
          case (None, None) =>
            List(McpServer.Local(name, command, args, dir, env + ("FS_ROOTS" -> mounts.roots.mkString(",")))).pure[F]
      case (name, McpServerSettings.Http(url, keyEnv, headers)) =>
        (keyEnv, secrets.mcpApiKeys.get(name)) match
          case (Some(env), None) =>
            Logger[F].warn(s"MCP server '$name' skipped: $env is not set").as(Nil)
          case (_, key) =>
            val auth = key.fold(Map.empty[String, String])(k => Map("Authorization" -> s"Bearer ${k.value}"))
            List(McpServer.Remote(name, url, headers ++ auth)).pure[F]
    }

  /** Unknown tool names are a config error, unless a server was skipped (its tools are then unknowable). */
  private def subAgentSpecs[F[_]: Async](config: Config, available: Set[String], skipped: Set[String], log: AgentLog[F]): F[List[SubAgentSpec]] =
    config.subagents.toList.sortBy(_._1).flatTraverse { (name, s) =>
      val spec    = SubAgentSpec(name, s.description, s.instructions, s.tools, s.model, s.maxRounds)
      val unknown = s.tools.filterNot(available)
      if unknown.isEmpty then List(spec).pure[F]
      else if skipped.nonEmpty then
        log.warn(s"subagent '$name' disabled: tools not available (${unknown.mkString(", ")})").as(Nil)
      else Async[F].raiseError(IllegalArgumentException(s"subagent '$name' references unknown tools: ${unknown.mkString(", ")}"))
    }

  /** `{{MOUNT}}`/`{{SANDBOX_DIR}}`: the workspace; `{{SESSION_MOUNT}}`/`{{SESSION_DIR}}`: this run's session folder. */
  private def instructions(template: String, mounts: Mounts): String =
    template
      .replace("{{MOUNT}}", mounts.workspaceName)
      .replace("{{SANDBOX_DIR}}", mounts.workspace.toString)
      .replace("{{SESSION_MOUNT}}", mounts.sessionName)
      .replace("{{SESSION_DIR}}", mounts.session.toString)

  private def banner(config: Config, where: Locations, mounts: Mounts, skills: List[String], agents: List[String]): String =
    val lines = List(
      s"yama ${Version.current}: Yet Another Micro Agent",
      s"config:   ${where.configFile}",
      s"provider: ${config.chat.provider}",
      s"model:    ${config.chat.model}",
      s"sandbox:  ${mounts.workspace}",
      s"session:  ${mounts.session}",
      s"skills:   ${if skills.isEmpty then "none" else skills.mkString(", ")}",
      s"agents:   ${if agents.isEmpty then "none" else agents.mkString(", ")}"
    )
    val width = lines.map(_.length).max + 2
    (("─" * width) :: lines.map(" " + _) ::: List("─" * width)).mkString("\n")
