package yama.skills.internal

import cats.syntax.all.*
import io.circe.{Decoder, Json}
import io.circe.yaml.parser as yaml

/** `SKILL.md` = YAML frontmatter between `---` delimiters + a markdown body. */
private[skills] final case class Frontmatter(
    name: Option[String],
    description: Option[String],
    license: Option[String],
    compatibility: Option[String],
    metadata: Map[String, String],
    allowedTools: Option[String]
)

private[skills] object Frontmatter:
  given Decoder[Frontmatter] = Decoder.instance { c =>
    (
      c.get[Option[String]]("name"),
      c.get[Option[String]]("description"),
      c.get[Option[String]]("license"),
      c.get[Option[String]]("compatibility"),
      c.getOrElse[Map[String, String]]("metadata")(Map.empty),
      c.get[Option[String]]("allowed-tools")
    ).mapN(Frontmatter.apply)
  }

  private val NamePattern = "^[a-z0-9]+(-[a-z0-9]+)*$".r

  /** Splits the file; returns the frontmatter and the trimmed body. */
  def parse(content: String): Either[String, (Frontmatter, String)] =
    val lines = content.linesIterator.toVector
    if lines.headOption.map(_.trim) != Some("---") then Left("missing YAML frontmatter (file must start with ---)")
    else
      val end = lines.indexWhere(_.trim == "---", 1)
      if end < 0 then Left("unterminated YAML frontmatter (no closing ---)")
      else
        val raw  = lines.slice(1, end).mkString("\n")
        val body = lines.drop(end + 1).mkString("\n").trim
        decode(raw).orElse(decode(requote(raw))).map(_ -> body)

  private def decode(raw: String): Either[String, Frontmatter] =
    yaml.parse(raw).leftMap(_.getMessage).flatMap { json =>
      if json.isObject then json.as[Frontmatter].leftMap(_.getMessage) else Left("frontmatter is not a YAML mapping")
    }

  /** The most common invalid YAML in the wild: an unquoted value containing `: `. */
  private def requote(raw: String): String =
    raw.linesIterator
      .map {
        case line @ s"$key: $value"
            if !line.startsWith(" ") && value.contains(": ") && !value.startsWith("\"") && !value.startsWith("'") =>
          s"$key: ${Json.fromString(value).noSpaces}"
        case line => line
      }
      .mkString("\n")

  /** Lenient: rule violations are warnings, the skill still loads. */
  def nameWarnings(name: String, directory: String): List[String] =
    List(
      Option.when(name.length > 64)(s"name '$name' is longer than 64 characters"),
      Option.when(NamePattern.findFirstIn(name).isEmpty)(s"name '$name' must be lowercase a-z, 0-9 and single hyphens"),
      Option.when(name != directory)(s"name '$name' does not match its directory '$directory'")
    ).flatten
