package yama.skills.internal

import yama.skills.{Diagnostic, SkillMeta}
import yama.skills.Skills.Root

import java.nio.file.{Files, Path}
import scala.jdk.CollectionConverters.*
import scala.util.Using

/** A skill is a subdirectory containing a file named exactly `SKILL.md`. Blocking IO: run it in `Sync.blocking`. */
private[skills] object Discovery:
  private val MaxDepth = 4
  private val Skipped  = Set(".git", "node_modules", "target")

  final case class Found(meta: SkillMeta, body: String)

  def scan(roots: List[Root]): (List[Found], List[Diagnostic]) =
    val results = for
      root     <- roots.distinctBy(_.dir.toAbsolutePath.normalize)
      skillDir <- skillDirs(root.dir.toAbsolutePath.normalize)
    yield load(skillDir.resolve("SKILL.md"), root)
    val loaded      = results.collect { case Right((found, warnings)) => (found, warnings) }
    val diagnostics = results.collect { case Left(error) => error } ++ loaded.flatMap(_._2)
    // Roots are ordered by precedence (project first), so the first skill with a name wins.
    val (kept, collisions) = loaded.map(_._1).foldLeft((Vector.empty[Found], List.empty[Diagnostic])) {
      case ((kept, diags), found) =>
        kept.find(_.meta.name == found.meta.name) match
          case Some(winner) =>
            (kept, diags :+ Diagnostic(found.meta.location, s"'${found.meta.name}' shadowed by ${winner.meta.location}", skipped = true))
          case None => (kept :+ found, diags)
    }
    (kept.toList, diagnostics ++ collisions)

  private def skillDirs(root: Path): List[Path] =
    if !Files.isDirectory(root) then Nil
    else
      Using.resource(Files.walk(root, MaxDepth)) { stream =>
        stream.iterator.asScala
          .filter(p => p.getFileName.toString == "SKILL.md" && Files.isRegularFile(p))
          .filterNot(p => root.relativize(p).iterator.asScala.exists(seg => Skipped(seg.toString)))
          .map(_.getParent)
          .toList
          .sorted
      }

  private def load(file: Path, root: Root): Either[Diagnostic, (Found, List[Diagnostic])] =
    val directory = file.getParent.getFileName.toString
    for
      (fm, body) <- Frontmatter.parse(Files.readString(file)).left.map(e => Diagnostic(file, e, skipped = true))
      description <- fm.description.map(_.trim).filter(_.nonEmpty).toRight(Diagnostic(file, "missing description", skipped = true))
    yield
      val name     = fm.name.map(_.trim).filter(_.nonEmpty).getOrElse(directory)
      val warnings =
        (if fm.name.isEmpty then List(s"missing name, using directory name '$directory'") else Frontmatter.nameWarnings(name, directory)) ++
          Option.when(description.length > 1024)("description is longer than 1024 characters")
      (Found(SkillMeta(name, description, file, root.scope), body), warnings.map(Diagnostic(file, _, skipped = false)))

  /** Resource files of a skill (everything but `SKILL.md`), relative to its directory. Listed, not read. */
  def resources(dir: Path): List[String] =
    Using.resource(Files.walk(dir, MaxDepth)) { stream =>
      stream.iterator.asScala
        .filter(Files.isRegularFile(_))
        .map(p => dir.relativize(p).toString.replace('\\', '/'))
        .filterNot(_ == "SKILL.md")
        .toList
        .sorted
        .take(50)
    }
