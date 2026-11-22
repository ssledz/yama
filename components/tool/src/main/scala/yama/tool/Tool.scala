package yama.tool

import io.circe.Json
import yama.conversation.ToolName

/** A tool = schema + handler. The model reads `name`, `description` and `schema`; we run `run`. */
trait Tool[F[_]]:
  def name: ToolName
  def description: String
  def schema: Json
  def run(args: Json): F[Json]

  /** Where the tool lives, for the log: `native`, `mcp:files`, `skill`, `agent`. */
  def origin: String = "native"

  /** True when the schema is eligible for provider-side strict validation (all fields required). */
  def strict: Boolean = false

object Tool:
  def apply[F[_]](
      name: String,
      description: String,
      schema: Json,
      origin: String = "native",
      strict: Boolean = false
  )(run: Json => F[Json]): Tool[F] =
    val (n, d, s, o, st, r) = (ToolName(name), description, schema, origin, strict, run)
    new Tool[F]:
      def name: ToolName             = n
      def description: String        = d
      def schema: Json               = s
      def run(args: Json): F[Json]   = r(args)
      override def origin: String    = o
      override def strict: Boolean   = st

  /** The failure shape every tool error takes: data, not an exception. */
  def error(message: String): Json = Json.obj("error" -> Json.fromString(message))

  def isError(output: Json): Boolean = output.hcursor.downField("error").succeeded
