package yama.skills.internal

import cats.effect.{Ref, Sync}
import cats.syntax.all.*
import io.circe.{Decoder, Json}
import io.circe.syntax.*
import yama.skills.{Diagnostic, Scope, SkillMeta, Skills}
import yama.tool.Tool

import java.nio.file.Files

private[skills] object SkillTools:
  private val MaxResourceBytes = 256 * 1024

  def make[F[_]: Sync](found: List[Discovery.Found], diags: List[Diagnostic], active: Ref[F, Set[String]]): Skills[F] =
    val byName = found.map(f => f.meta.name -> f).toMap
    new Skills[F]:
      def catalog: List[SkillMeta]      = found.map(_.meta)
      def diagnostics: List[Diagnostic] = diags
      def reset: F[Unit]                = active.set(Set.empty)

      def activate(name: String): F[Either[String, String]] =
        byName.get(name) match
          case None => s"Unknown skill: $name".asLeft[String].pure[F]
          case Some(skill) =>
            active.modify(names => (names + name, names(name))).flatMap { already =>
              if already then s"Skill '$name' is already active: its instructions are earlier in this conversation.".asRight[String].pure[F]
              else Sync[F].blocking(Discovery.resources(skill.meta.directory)).map(render(skill, _).asRight[String])
            }

      def readResource(name: String, path: String): F[Either[String, String]] =
        byName.get(name) match
          case None => s"Unknown skill: $name".asLeft[String].pure[F]
          case Some(skill) =>
            val dir    = skill.meta.directory.toAbsolutePath.normalize
            val target = dir.resolve(path).normalize
            if !target.startsWith(dir) || target == dir then s"Refused: $path resolves outside the skill directory".asLeft[String].pure[F]
            else
              Sync[F].blocking {
                if !Files.isRegularFile(target) then Left(s"No such resource: $path")
                else if Files.size(target) > MaxResourceBytes then Left(s"Resource too large: $path")
                else Right(Files.readString(target))
              }

      def tools: List[Tool[F]] = if found.isEmpty then Nil else List(activateTool(this), readResourceTool(this))

      def catalogPrompt: String =
        if found.isEmpty then ""
        else
          val entries = catalog.map { s =>
            s"  <skill>\n    <name>${xml(s.name)}</name>\n    <description>${xml(s.description)}</description>\n  </skill>"
          }
          s"""SKILLS
             |The following skills provide specialized instructions for specific tasks.
             |When a task matches a skill's description, call the `activate_skill` tool with the skill's name to load
             |its full instructions before you start. Load resources a skill refers to with `read_skill_resource`.
             |<available_skills>
             |${entries.mkString("\n")}
             |</available_skills>""".stripMargin

  private def render(skill: Discovery.Found, resources: List[String]): String =
    val files =
      if resources.isEmpty then ""
      else resources.map(r => s"<file>${xml(r)}</file>").mkString("\n<skill_resources>\n", "\n", "\n</skill_resources>")
    // a built-in skill lives inside yama's jar: no path the model could use, only read_skill_resource
    val where =
      if skill.meta.scope == Scope.Builtin then "built into yama (read its files with read_skill_resource)"
      else skill.meta.directory.toString
    s"""<skill_content name="${xml(skill.meta.name)}">
       |${skill.body}
       |
       |Skill directory: $where
       |Relative paths in this skill are relative to the skill directory.$files
       |</skill_content>""".stripMargin

  private def xml(text: String): String = text.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")

  private final case class ActivateArgs(name: String) derives Decoder
  private final case class ResourceArgs(name: String, path: String) derives Decoder

  private def enumOf(names: List[String]): Json = Json.obj("type" -> "string".asJson, "enum" -> names.asJson)

  private def result(r: Either[String, String]): Json = r.fold(Tool.error, _.asJson)

  /** `name` is an enum of the discovered names, so the model can't make up a skill. */
  private def activateTool[F[_]: Sync](skills: Skills[F]): Tool[F] =
    Tool[F](
      name = "activate_skill",
      description = "Load the full instructions of a skill listed in <available_skills>. Call it before starting a task that matches the skill's description.",
      schema = Json.obj(
        "type"                 -> "object".asJson,
        "properties"           -> Json.obj("name" -> enumOf(skills.catalog.map(_.name))),
        "required"             -> List("name").asJson,
        "additionalProperties" -> Json.False
      ),
      origin = "skill",
      strict = true
    )(json => json.as[ActivateArgs].liftTo[F].flatMap(a => skills.activate(a.name)).map(result))

  private def readResourceTool[F[_]: Sync](skills: Skills[F]): Tool[F] =
    Tool[F](
      name = "read_skill_resource",
      description = "Read a file that an activated skill refers to (listed in its <skill_resources>). Paths are relative to the skill directory.",
      schema = Json.obj(
        "type" -> "object".asJson,
        "properties" -> Json.obj(
          "name" -> enumOf(skills.catalog.map(_.name)),
          "path" -> Json.obj("type" -> "string".asJson, "description" -> "e.g. 'references/style.md'".asJson)
        ),
        "required"             -> List("name", "path").asJson,
        "additionalProperties" -> Json.False
      ),
      origin = "skill",
      strict = true
    )(json => json.as[ResourceArgs].liftTo[F].flatMap(a => skills.readResource(a.name, a.path)).map(result))
