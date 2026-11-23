package yama.cli

import cats.effect.Sync
import cats.effect.std.Console
import cats.syntax.all.*
import io.circe.syntax.*
import yama.agent.Agent
import yama.log.{Ansi, Stats}
import yama.skills.Skills
import yama.tool.Tool

import java.io.EOFException

/** Read an instruction → give it to the agent → print the answer. `Console[F]` is for user I/O only; logs stay separate. */
final class Repl[F[_]: Sync: Console](
    agent: Agent[F],
    tools: List[Tool[F]],
    prompt: String,
    skills: Skills[F],
    stats: Stats[F]
):
  private val console = Console[F]

  private val commands = List(
    "/help"    -> "show this help",
    "/tools"   -> "list the tools the agent can call",
    "/prompt"  -> "print the system instructions",
    "/history" -> "print the raw conversation the model sees",
    "/stats"   -> "token usage so far",
    "/skills"  -> "list skills and discovery diagnostics",
    "/skill"   -> "/skill <name> <instruction>: run an instruction with a skill pre-loaded",
    "/reset"   -> "forget the conversation and reset counters",
    "/exit"    -> "quit (ctrl-d works too)"
  )

  def run: F[Unit] =
    help >> console.println(Ansi.dim("try: download https://example.com and save a short summary as example.md")) >> loop

  private def loop: F[Unit] =
    console.print(Ansi.bold(Ansi.blue("\nyama> "))) >>
      console.readLine.map(Option(_)).recover { case _: EOFException => None }.flatMap {
        case None                      => console.println("")
        case Some(line) if line.trim == "/exit" => Sync[F].unit
        case Some(line)                => handle(line.trim) >> loop
      }

  private def handle(input: String): F[Unit] = input match
    case ""          => Sync[F].unit
    case "/help"     => help
    case "/tools"    => tools.traverse_(t => console.println(s"  ${Ansi.bold(t.name.value)} ${Ansi.dim(s"[${t.origin}] ${t.description.linesIterator.nextOption().getOrElse("")}")}"))
    case "/prompt"   => console.println(Ansi.dim(prompt))
    case "/history"  => agent.history.flatMap(h => console.println(h.asJson.spaces2))
    case "/stats"    => stats.get.flatMap(s => console.println(Stats.render(s)))
    case "/skills"   => listSkills
    case "/reset"    => agent.reset >> stats.reset >> skills.reset >> console.println(Ansi.green("✓ conversation and counters cleared"))
    case s"/skill $name $instruction" =>
      skills.activate(name).flatMap {
        case Left(error)    => console.errorln(error)
        case Right(content) => ask(s"$content\n\n$instruction")
      }
    case command if command.startsWith("/") => console.println(s"unknown command $command, try /help")
    case instruction => ask(instruction)

  private def ask(instruction: String): F[Unit] =
    agent.query(instruction).attempt.flatMap {
      case Right(answer) =>
        console.println(s"\n${Ansi.bold(Ansi.bgGreen(" ANSWER "))}\n${answer.text}") >>
          stats.get.flatMap(s => console.println(Ansi.dim(Stats.render(s))))
      case Left(error) => console.errorln(Ansi.red(s"✗ agent failed: ${error.getMessage}"))
    }

  private def help: F[Unit] =
    commands.traverse_((cmd, text) => console.println(s"  ${Ansi.cyan(cmd.padTo(10, ' '))} ${Ansi.dim(text)}")) >>
      console.println(Ansi.dim("  anything else is sent to the agent as an instruction"))

  private def listSkills: F[Unit] =
    val catalog = skills.catalog.traverse_(s => console.println(s"  ${Ansi.bold(s.name)} ${Ansi.dim(s"[${s.scope}] ${s.description}")}"))
    val diags   = skills.diagnostics.traverse_(d => console.println(Ansi.yellow(s"  ⚠ ${d.location}: ${d.message}")))
    (if skills.catalog.isEmpty then console.println("  no skills found") else catalog) >> diags
