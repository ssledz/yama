package yama.tool

import io.circe.Json

import scala.compiletime.{constValueTuple, summonAll}
import scala.deriving.Mirror

/** JSON Schema for a tool's arguments, derived from a case class:
  * {{{
  * case class WgetArgs(url: String, outputPath: String) derives ToolSchema, Decoder
  * }}}
  */
trait ToolSchema[A]:
  def json: Json
  def optional: Boolean = false

object ToolSchema:
  def apply[A](using schema: ToolSchema[A]): ToolSchema[A] = schema

  private def of[A](schema: Json): ToolSchema[A] = new ToolSchema[A] { val json = schema }
  private def tpe(name: String): Json            = Json.obj("type" -> Json.fromString(name))

  given ToolSchema[String]  = of(tpe("string"))
  given ToolSchema[Int]     = of(tpe("integer"))
  given ToolSchema[Long]    = of(tpe("integer"))
  given ToolSchema[Double]  = of(tpe("number"))
  given ToolSchema[Boolean] = of(tpe("boolean"))

  given [A](using item: ToolSchema[A]): ToolSchema[List[A]] =
    of(tpe("array").deepMerge(Json.obj("items" -> item.json)))

  /** An `Option` field is the only way to make an argument not required. */
  given [A](using inner: ToolSchema[A]): ToolSchema[Option[A]] = new ToolSchema[Option[A]]:
    val json                       = inner.json
    override def optional: Boolean = true

  /** Field names and field types come from the case class `Mirror`, at compile time. */
  inline def derived[A](using m: Mirror.ProductOf[A]): ToolSchema[A] =
    val names  = constValueTuple[m.MirroredElemLabels].toList.map(_.toString)
    val fields = summonAll[Tuple.Map[m.MirroredElemTypes, ToolSchema]].toList.map(_.asInstanceOf[ToolSchema[?]])
    objectSchema(names.zip(fields))

  private def objectSchema[A](fields: List[(String, ToolSchema[?])]): ToolSchema[A] =
    val schema = Json.obj(
      "type"                 -> Json.fromString("object"),
      "properties"           -> Json.obj(fields.map((name, s) => name -> s.json)*),
      "required"             -> Json.arr(fields.collect { case (name, s) if !s.optional => Json.fromString(name) }*),
      "additionalProperties" -> Json.False
    )
    new ToolSchema[A]:
      val json = schema

  extension [A](schema: ToolSchema[A])
    /** True when every field is required, which is what strict mode wants. */
    def allRequired: Boolean =
      schema.json.hcursor.downField("properties").keys.map(_.size).getOrElse(0) ==
        schema.json.hcursor.downField("required").values.map(_.size).getOrElse(0)

    /** Adds a `description` to fields: the description is part of the prompt. */
    def describe(descriptions: (String, String)*): Json =
      descriptions.foldLeft(schema.json) { case (json, (field, text)) =>
        json.hcursor
          .downField("properties")
          .downField(field)
          .withFocus(_.deepMerge(Json.obj("description" -> Json.fromString(text))))
          .top
          .getOrElse(json)
      }
