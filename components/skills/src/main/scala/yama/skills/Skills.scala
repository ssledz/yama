package yama.skills

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import yama.skills.internal.{Discovery, SkillTools}
import yama.tool.Tool

import java.net.URI
import java.nio.file.{FileSystemAlreadyExistsException, FileSystems, Path, Paths}
import scala.jdk.CollectionConverters.*

/** Where a skill was found. On a name collision project beats user, and user beats the skills built into yama. */
enum Scope:
  case Project, User, Builtin

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

  /** Classpath folder the build copies the repo's `skills/` into; `.root` marks it so it can be found in a jar too. */
  val BuiltinResource = "yama-skills"

  /** `<project>/.yama/skills`, `<project>/.agents/skills` (only when trusted), `<userSkills>` (normally
    * `~/.yama/skills`), `~/.agents/skills`, plus extra paths from config (project-level), then the built-in ones.
    */
  def defaultRoots(project: Path, userSkills: Path, home: Path, extra: List[Path], trustProject: Boolean, builtin: Option[Path]): List[Root] =
    val projectRoots =
      if trustProject then (List(project.resolve(".yama/skills"), project.resolve(".agents/skills")) ++ extra).map(Root(_, Scope.Project))
      else Nil
    projectRoots ++ List(userSkills, home.resolve(".agents/skills")).map(Root(_, Scope.User)) ++ builtin.map(Root(_, Scope.Builtin))

  /** The built-in skills, read from the classpath: a plain folder under sbt, a zip filesystem inside the fat jar.
    * `None` when they were not packaged.
    */
  def builtinRoot[F[_]: Sync]: F[Option[Path]] =
    Sync[F].blocking {
      Option(getClass.getClassLoader.getResource(s"$BuiltinResource/.root")).map { url =>
        val uri = url.toURI
        if uri.getScheme == "jar" then
          val jar = URI.create(uri.toString.takeWhile(_ != '!'))
          val fs  =
            try FileSystems.newFileSystem(jar, Map.empty[String, Any].asJava)
            catch case _: FileSystemAlreadyExistsException => FileSystems.getFileSystem(jar)
          fs.getPath(s"/$BuiltinResource")
        else Paths.get(uri).getParent
      }
    }

  def discover[F[_]: Sync](roots: List[Root]): F[Skills[F]] =
    for
      (found, diagnostics) <- Sync[F].blocking(Discovery.scan(roots))
      active               <- Ref.of[F, Set[String]](Set.empty)
    yield SkillTools.make(found, diagnostics, active)
