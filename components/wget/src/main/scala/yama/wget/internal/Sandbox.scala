package yama.wget.internal

import java.nio.file.{Path, Paths}

/** Least privilege in code, not in the prompt: every path the model invents must resolve inside one of the mounts.
  * Paths are addressed by mount name, like the files MCP server does it: `<mount>/downloads/page.html`.
  * A path with no mount prefix lands in the first mount.
  */
private[wget] final case class Sandbox(mounts: List[(String, Path)]):
  require(mounts.nonEmpty, "a sandbox needs at least one mount")

  def names: List[String] = mounts.map(_._1)

  def resolve(requested: String): Either[String, Path] =
    val cleaned = requested.replace('\\', '/').replaceFirst("^\\./+", "")
    val target  =
      if Paths.get(requested).isAbsolute then Paths.get(requested).normalize
      else
        mounts
          .collectFirst { case (name, root) if cleaned == name || cleaned.startsWith(s"$name/") => root.resolve(cleaned.drop(name.length + 1)) }
          .getOrElse(mounts.head._2.resolve(cleaned))
          .normalize
    Either.cond(
      mounts.exists((_, root) => target.startsWith(root) && target != root),
      target,
      s"Refused: $requested resolves outside the sandbox (${names.map(_ + "/").mkString(", ")})"
    )

  def display(path: Path): String =
    mounts.collectFirst { case (name, root) if path.startsWith(root) => s"$name/${root.relativize(path).toString.replace('\\', '/')}" }
      .getOrElse(path.toString)

private[wget] object Sandbox:
  def of(roots: List[Path]): Sandbox =
    val absolute = roots.map(_.toAbsolutePath.normalize)
    new Sandbox(mountNames(absolute).zip(absolute))

  /** Same rule as files-mcp: the folder name, with `_2`, `_3`… on a clash, in the order given. */
  def mountNames(roots: List[Path]): List[String] =
    roots.foldLeft((Vector.empty[String], Set.empty[String])) { case ((names, used), root) =>
      val base   = Option(root.toAbsolutePath.normalize.getFileName).map(_.toString).filter(_.nonEmpty).getOrElse("root")
      val unique = (Iterator.single(base) ++ Iterator.from(2).map(i => s"${base}_$i")).find(n => !used(n)).get
      (names :+ unique, used + unique)
    }._1.toList
