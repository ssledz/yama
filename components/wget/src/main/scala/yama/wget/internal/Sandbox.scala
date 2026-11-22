package yama.wget.internal

import java.nio.file.{Path, Paths}

/** Least privilege in code, not in the prompt: every path the model invents must resolve inside the sandbox.
  * Paths are addressed by mount name, like the files MCP server does it: `sandbox/downloads/page.html`.
  */
private[wget] final case class Sandbox(root: Path):
  val mount: String = root.getFileName.toString

  def resolve(requested: String): Either[String, Path] =
    val cleaned  = requested.replace('\\', '/').replaceFirst("^\\./+", "")
    val relative =
      if cleaned == mount then ""
      else if cleaned.startsWith(s"$mount/") then cleaned.drop(mount.length + 1)
      else cleaned
    val target =
      if Paths.get(requested).isAbsolute then Paths.get(requested).normalize
      else root.resolve(relative).normalize
    Either.cond(
      target.startsWith(root) && target != root,
      target,
      s"Refused: $requested resolves outside the sandbox ($mount/)"
    )

  def display(path: Path): String = s"$mount/${root.relativize(path).toString.replace('\\', '/')}"

private[wget] object Sandbox:
  def apply(root: Path): Sandbox = new Sandbox(root.toAbsolutePath.normalize)
