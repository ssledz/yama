package yama.chat.openai.internal

import io.circe.Json
import io.circe.parser.parse
import munit.FunSuite
import org.http4s.Headers
import org.typelevel.ci.*
import yama.chat.{ChatError, ToolSpec, Usage}
import yama.conversation.{CallId, Item, ToolName}

import scala.concurrent.duration.*
import scala.io.Source

/** Golden JSON: `Item` list → request JSON, and recorded responses → `Reply`. No network needed. */
class WireFormatSpec extends FunSuite:

  private def golden(name: String): Json =
    val text = Source.fromResource(s"golden/$name").mkString
    parse(text).fold(e => fail(s"$name: ${e.message}"), identity)

  private def schema(s: String): Json = parse(s).toOption.get

  test("encodes the conversation and tools as a stateless Responses API request") {
    val conversation = Vector(
      Item.User("download https://example.com"),
      Item.ToolCall(CallId("call_1"), ToolName("wget"), """{"url":"https://example.com","outputPath":"sandbox/downloads/e.html"}"""),
      Item.ToolResult(CallId("call_1"), Json.obj("success" -> Json.True, "bytes" -> Json.fromInt(1256))),
      Item.Assistant("Saved it.")
    )
    val tools = List(
      ToolSpec(ToolName("wget"), "Download a URL.", schema("""{"type":"object","properties":{"url":{"type":"string"}},"required":["url"],"additionalProperties":false}"""), strict = true),
      ToolSpec(ToolName("fs_read"), "Read a file.", schema("""{"type":"object","properties":{"path":{"type":"string"}}}"""))
    )
    val request = WireFormat.request("gpt-4.1", "be helpful", tools, conversation, strictTools = true)
    assertEquals(request, golden("request.json"))
  }

  test("strict is only sent when the provider supports it") {
    val tool    = ToolSpec(ToolName("t"), "", Json.obj(), strict = true)
    val request = WireFormat.request("m", "", List(tool), Vector.empty, strictTools = false)
    assertEquals(request.hcursor.downField("tools").downArray.get[Boolean]("strict"), Right(false))
  }

  test("no tools means no tools and no tool_choice fields") {
    val request = WireFormat.request("m", "i", Nil, Vector(Item.User("hi")), strictTools = false)
    assert(!request.asObject.exists(_.contains("tools")))
    assert(!request.asObject.exists(_.contains("tool_choice")))
  }

  test("decodes parallel function calls and usage") {
    val reply = WireFormat.reply(golden("openai-tool-calls.json")).fold(fail(_), identity)
    assertEquals(
      reply.toolCalls,
      Vector(
        Item.ToolCall(CallId("call_wget_1"), ToolName("wget"), """{"url":"https://artofscala.com","outputPath":"sandbox/downloads/aos.html"}"""),
        Item.ToolCall(CallId("call_read_1"), ToolName("fs_read"), """{"path":"sandbox/"}""")
      )
    )
    assertEquals(reply.text, None)
    assertEquals(reply.usage, Usage(1520, 61, Some(1024)))
  }

  test("decodes a final text answer") {
    val reply = WireFormat.reply(golden("openai-message.json")).fold(fail(_), identity)
    assertEquals(reply.output, Vector(Item.Assistant("I saved a summary to sandbox/aos.md.")))
    assertEquals(reply.toolCalls, Vector.empty)
    assertEquals(reply.usage, Usage(2100, 14, None))
  }

  test("ignores reasoning items and keeps text next to tool calls") {
    val reply = WireFormat.reply(golden("reasoning-and-call.json")).fold(fail(_), identity)
    assertEquals(
      reply.output,
      Vector(Item.Assistant("Let me check the folder."), Item.ToolCall(CallId("call_9"), ToolName("fs_read"), """{"path":"sandbox/"}"""))
    )
  }

  test("an error object in the body is an error") {
    assert(WireFormat.reply(golden("error-429.json")).isLeft)
  }

  test("429 is retryable and takes the delay from the 'try again in' hint") {
    val error = HttpChat.error(429, Headers.empty, golden("error-429.json").noSpaces)
    error match
      case ChatError.Retryable(429, detail, Some(after)) =>
        assert(detail.startsWith("Rate limit reached"))
        assertEquals(after, 2500.millis)
      case other => fail(s"unexpected $other")
  }

  test("Retry-After header wins over the body hint") {
    val headers = Headers("Retry-After" -> "7")
    assertEquals(HttpChat.retryAfter(headers), Some(7.seconds))
    assertEquals(HttpChat.error(503, headers, "{}"), ChatError.Retryable(503, "{}", Some(7.seconds)))
  }

  test("4xx other than 408/409/429 is fatal") {
    assertEquals(
      HttpChat.error(401, Headers.empty, """{"error":{"message":"Incorrect API key"}}"""),
      ChatError.Fatal(401, "Incorrect API key")
    )
    assertEquals(ci"Retry-After".toString, "Retry-After")
  }
