package yama.chat.openai.internal

import cats.syntax.all.*
import io.circe.{ACursor, Json}
import io.circe.syntax.*
import yama.chat.{Reply, ToolSpec, Usage}
import yama.conversation.{CallId, Item, ToolName}

/** `Item` ↔ OpenAI Responses API JSON. Stateless: the whole transcript is sent every round, `store: false`. */
private[openai] object WireFormat:

  def request(
      model: String,
      instructions: String,
      tools: List[ToolSpec],
      conversation: Vector[Item],
      strictTools: Boolean
  ): Json =
    Json
      .obj(
        "model"        -> model.asJson,
        "instructions" -> instructions.asJson,
        "input"        -> conversation.map(item).asJson,
        "store"        -> Json.False
      )
      .deepMerge(
        if tools.isEmpty then Json.obj()
        else Json.obj("tools" -> tools.map(tool(_, strictTools)).asJson, "tool_choice" -> "auto".asJson)
      )

  private def item(i: Item): Json = i match
    case Item.User(text)      => message("user", text)
    case Item.Assistant(text) => message("assistant", text)
    case Item.ToolCall(id, name, args) =>
      Json.obj(
        "type"      -> "function_call".asJson,
        "call_id"   -> id.value.asJson,
        "name"      -> name.value.asJson,
        "arguments" -> args.asJson
      )
    case Item.ToolResult(id, output) =>
      Json.obj(
        "type"    -> "function_call_output".asJson,
        "call_id" -> id.value.asJson,
        "output"  -> output.noSpaces.asJson
      )

  private def message(role: String, text: String): Json =
    Json.obj("type" -> "message".asJson, "role" -> role.asJson, "content" -> text.asJson)

  private def tool(spec: ToolSpec, strictTools: Boolean): Json =
    Json.obj(
      "type"        -> "function".asJson,
      "name"        -> spec.name.value.asJson,
      "description" -> spec.description.asJson,
      "parameters"  -> spec.parameters,
      "strict"      -> (strictTools && spec.strict).asJson
    )

  /** `output` is a list of `message` / `function_call` items (others, e.g. `reasoning`, are ignored). */
  def reply(json: Json): Either[String, Reply] =
    val c = json.hcursor
    c.downField("error").focus.filterNot(_.isNull) match
      case Some(error) =>
        Left(error.hcursor.get[String]("message").getOrElse(error.noSpaces))
      case None =>
        for
          output <- c.downField("output").as[Vector[Json]].leftMap(_ => s"no 'output' in response: ${json.noSpaces.take(300)}")
          items  <- output.flatTraverse(outputItem)
        yield Reply(items, usage(c.downField("usage")))

  private def outputItem(json: Json): Either[String, Vector[Item]] =
    val c = json.hcursor
    c.get[String]("type").getOrElse("") match
      case "message" =>
        val texts = c.downField("content").values.toVector.flatten.flatMap { part =>
          part.hcursor.get[String]("text").toOption
        }
        Right(Option(texts.mkString).filter(_.trim.nonEmpty).map(Item.Assistant(_)).toVector)
      case "function_call" =>
        (c.get[String]("call_id"), c.get[String]("name"), c.get[String]("arguments"))
          .mapN((id, name, args) => Vector(Item.ToolCall(CallId(id), ToolName(name), args)))
          .leftMap(e => s"malformed function_call: ${e.getMessage}")
      case _ => Right(Vector.empty)

  private def usage(c: ACursor): Usage =
    Usage(
      inputTokens = c.get[Long]("input_tokens").getOrElse(0L),
      outputTokens = c.get[Long]("output_tokens").getOrElse(0L),
      cachedTokens = c.downField("input_tokens_details").get[Long]("cached_tokens").toOption
    )
