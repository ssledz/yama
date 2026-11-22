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
import yama.config.{Config, McpServerSettings, Secrets}
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
      config  <- Resource.eval(Config.load[F](args.config, args.overrides))
      secrets <- Resource.eval(Secrets.load[F](config))
      project  = Paths.get("").toAbsolutePath
      sandbox  = project.resolve(config.sandbox.dir).normalize
      _       <- Resource.eval(Files[F].createDirectories(fs2.io.file.Path.fromNioPath(sandbox)))
      given Random[F] <- Resource.eval(Random.scalaUtilRandom[F])
      client  <- EmberClientBuilder.default[F].withTimeout(5.minutes).withIdleConnectionTime(10.minutes).build
      stats   <- Resource.eval(Stats.make[F])
      log      = AgentLog.make[F]
      retry    = Chat.RetryPolicyConfig(config.chat.retry.maxAttempts, config.chat.retry.baseDelayMs.millis, config.chat.retry.maxDelayMs.millis)
      provider = ProviderConfig(config.chat.provider, config.provider.baseUrl, config.provider.headers, config.provider.strictTools)
      chatFor  = (model: String) => Chat.retrying(retry)(OpenAiCompat.make(client, provider, model, secrets.chatApiKey))
      chat     = chatFor(config.chat.model)
      // MCP: local servers are spawned (FS_ROOT injected, so server and wget agree on the sandbox), remote ones connected.
      servers <- Resource.eval(mcpServers(config, secrets, project, sandbox))
      clients <- servers.traverse(server => McpClient.connect[F](server))
      mcp     <- Resource.eval(clients.traverse(c => McpTools.from(c).map(c.server -> _)))
      _       <- Resource.eval(mcp.traverse_((name, tools) => log.info(s"✓ MCP server '$name': ${tools.map(_.name.value).mkString(", ")}")))
      wget     = Wget.make[F](client, sandbox)
      skills  <- Resource.eval(Skills.discover[F](Skills.defaultRoots(project, Paths.get(sys.props("user.home")), config.skills.paths.map(project.resolve(_).normalize), config.skills.trustProject)))
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
      prompt   = instructions(config.agent.instructions, sandbox) + Option(skills.catalogPrompt).filter(_.nonEmpty).fold("")("\n\n" + _)
      agent   <- Resource.eval(Agent.make[F](Agent.Settings("yama", prompt, config.agent.maxRounds), chat, tools, log, stats))
      _       <- Resource.onFinalize(stats.get.flatMap(s => log.info(Stats.render(s))))
    yield (
      Repl[F](agent, tools.tools, prompt, skills, stats),
      banner(config, sandbox, skills.catalog.map(_.name), specs.map(_.name))
    )

  private def mcpServers[F[_]: Async: Logger](config: Config, secrets: Secrets, project: Path, sandbox: Path): F[List[McpServer]] =
    config.mcpServers.toList.sortBy(_._1).flatTraverse {
      case (name, McpServerSettings.Local(command, args, env, cwd)) =>
        val dir = cwd.map(project.resolve(_).normalize)
        val missingDeps = dir.filter(d => command == "npx" && !d.resolve("node_modules").toFile.isDirectory)
        missingDeps match
          case Some(d) =>
            Async[F].raiseError(IllegalStateException(s"MCP server '$name' is not installed: run `npm install --prefix ${project.relativize(d)}`"))
          case None =>
            List(McpServer.Local(name, command, args, dir, env + ("FS_ROOT" -> sandbox.toString))).pure[F]
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

  private def instructions(template: String, sandbox: Path): String =
    template.replace("{{MOUNT}}", sandbox.getFileName.toString).replace("{{SANDBOX_DIR}}", sandbox.toString)

  private def banner(config: Config, sandbox: Path, skills: List[String], agents: List[String]): String =
    val lines = List(
      "yama: Yet Another Micro Agent",
      s"provider: ${config.chat.provider}",
      s"model:    ${config.chat.model}",
      s"sandbox:  $sandbox",
      s"skills:   ${if skills.isEmpty then "none" else skills.mkString(", ")}",
      s"agents:   ${if agents.isEmpty then "none" else agents.mkString(", ")}"
    )
    val width = lines.map(_.length).max + 2
    (("─" * width) :: lines.map(" " + _) ::: List("─" * width)).mkString("\n")
