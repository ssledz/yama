package yama.skills

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import yama.skills.internal.{Discovery, SkillTools}
import yama.tool.Tool

import java.nio.file.Path

/** Where a skill was found. Project-level skills beat user-level ones on a name collision. */
enum Scope:
  case Project, User

/** Tier 1 of progressive disclosure: what goes into the system prompt at startup (~50-100 tokens per skill). */
final case class SkillMeta(name: String, description: String, location: Path, scope: Scope):
  def directory: Path = location.getParent

final case class Diagnostic(location: Path, message: String, skipped: Boolean)

/** Agent Skills (agentskills.io): a skill is a folder with `SKILL.md`, a prompt loaded on demand behind a tool. */
trait Skills[F[_]]:
  def catalog: List[SkillMeta]
  def diagnostics: List[Diagnostic]

  /** Tier 2: the body (frontmatter stripped), wrapped in `<skill_content>`, with its resources listed, not read. */
  def activate(name: String): F[Either[String, String]]

  /** Tier 3: one file from the skill's directory, read-only and confined to it. */
  def readResource(name: String, path: String): F[Either[String, String]]

  /** `activate_skill` and `read_skill_resource`; empty when no skills were found. */
  def tools: List[Tool[F]]

  /** Forget which skills were activated (the conversation that held them was reset). */
  def reset: F[Unit]

  /** The `<available_skills>` block plus usage instructions; empty when no skills were found. */
  def catalogPrompt: String

object Skills:
  final case class Root(dir: Path, scope: Scope)

  /** `<project>/.yama/skills`, `<project>/.agents/skills` (only when trusted), `~/.yama/skills`, `~/.agents/skills`,
    * plus extra paths from config (project-level).
    */
  def defaultRoots(project: Path, home: Path, extra: List[Path], trustProject: Boolean): List[Root] =
    val projectRoots =
      if trustProject then (List(project.resolve(".yama/skills"), project.resolve(".agents/skills")) ++ extra).map(Root(_, Scope.Project))
      else Nil
    projectRoots ++ List(home.resolve(".yama/skills"), home.resolve(".agents/skills")).map(Root(_, Scope.User))

  def discover[F[_]: Sync](roots: List[Root]): F[Skills[F]] =
    for
      (found, diagnostics) <- Sync[F].blocking(Discovery.scan(roots))
      active               <- Ref.of[F, Set[String]](Set.empty)
    yield SkillTools.make(found, diagnostics, active)
