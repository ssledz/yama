package yama.conversation

import io.circe.{Encoder, Json}
import io.circe.syntax.*

/** The name of a tool, as the model sees it (`wget`, `fs_read`, `research_agent`). */
opaque type ToolName = String

object ToolName:
  def apply(value: String): ToolName = value
  extension (name: ToolName) def value: String = name
  given Encoder[ToolName] = Encoder.encodeString

/** The id that ties a tool call to its result (`call_abc123`). */
opaque type CallId = String

object CallId:
  def apply(value: String): CallId = value
  extension (id: CallId) def value: String = id
  given Encoder[CallId] = Encoder.encodeString

/** One entry of the conversation. The whole conversation is the agent's memory: `Vector[Item]`. */
enum Item:
  case User(text: String)
  case Assistant(text: String)
  /** The model asks us to run a tool. `args` is the raw JSON text the model wrote (it may be invalid). */
  case ToolCall(id: CallId, name: ToolName, args: String)
  /** What the tool returned, fed back to the model. Failures are data too: `{"error": "..."}`. */
  case ToolResult(id: CallId, output: Json)

object Item:
  given Encoder[Item] = Encoder.instance {
    case User(text)                 => Json.obj("user" -> text.asJson)
    case Assistant(text)            => Json.obj("assistant" -> text.asJson)
    case ToolCall(id, name, args)   => Json.obj("tool_call" -> Json.obj("id" -> id.asJson, "name" -> name.asJson, "args" -> args.asJson))
    case ToolResult(id, output)     => Json.obj("tool_result" -> Json.obj("id" -> id.asJson, "output" -> output))
  }
