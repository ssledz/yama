package yama.chat

import io.circe.Json
import yama.conversation.ToolName

/** What the model is told about a tool: its name, when to use it (the description is the prompt) and the
  * JSON Schema of its arguments. `strict` = the schema is eligible for provider-side strict validation.
  */
final case class ToolSpec(name: ToolName, description: String, parameters: Json, strict: Boolean = false)
